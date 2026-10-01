package io.legado.app.ui.book.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheBookManifest
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isMedia
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isType
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.CacheBook
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 缓存管理页 ViewModel：按书目分类列出"有缓存的书"，并对缓存做增删/上传/使用
 *
 * 与旧存储管理页的分工：旧页（[StorageManageViewModel]）按**缓存类型**统计与清理系统缓存，
 * 仍然是本页"统计"Tab 的数据源；本类是参考分支"缓存管理"的按**书**视角。
 *
 * 缓存任务复用现有 [io.legado.app.model.CacheBook]（并发、重试、前台通知都已具备），
 * 不另起一套任务管理器；启动任务需要 Context，通过 [CacheTaskStarter] 由 Activity 注入。
 */
class CacheManageViewModel(
    private val cacheTaskStarter: CacheTaskStarter = CacheTaskStarter { _, _ -> 0 }
) : ViewModel() {

    private val _uiState = MutableStateFlow(CacheManageUiState())
    val uiState: StateFlow<CacheManageUiState> = _uiState.asStateFlow()

    /**
     * 一次性事件：提示与"用缓存打开某章"
     * 用 UNLIMITED：导航类事件不能丢，提示条数本身很少，排队可见即可
     */
    private val _events = Channel<CacheManageEvent>(Channel.UNLIMITED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var chapterJob: Job? = null
    private var sizeJob: Job? = null

    /** 每本书最近一次单行重算的时刻，用于节流缓存任务进度事件 */
    private val itemRefreshAt = hashMapOf<String, Long>()

    /** 本次加载读到的清单（bookUrl -> 清单）：逐本读文件或反复扫目录会把列表拖慢 */
    private var manifestCache: Map<String, CacheBookManifest> = emptyMap()

    /** 已算好的每本缓存概况（bookUrl -> 已缓存章节数 to 占用），切分类/重进页面不重复扫盘 */
    private val computedByBookUrl = hashMapOf<String, Pair<Int, Long>>()

    init {
        load()
    }

    /**
     * 切换分类并重新加载
     */
    fun switchMode(mode: CacheManageMode) {
        if (mode == _uiState.value.mode && _uiState.value.items.isNotEmpty()) return
        load(mode)
    }

    /** 当前分类未过滤的全量列表，搜索只在内存里筛它 */
    private var allItems: List<CacheBookItem> = emptyList()

    /** 搜索当前分类的书（书名/作者/书源） */
    fun setSearchKey(key: String) {
        if (_uiState.value.searchKey == key) return
        _uiState.update { it.copy(searchKey = key) }
        applyFilter()
    }

    fun switchSearching(searching: Boolean) {
        _uiState.update { it.copy(searching = searching) }
        if (!searching && _uiState.value.searchKey.isNotEmpty()) {
            setSearchKey("")
        }
    }

    /**
     * 按关键字过滤当前分类的列表
     *
     * 只筛内存里的 allItems：每次输入都重新查库、扫缓存目录会把搜索框拖卡
     */
    private fun applyFilter() {
        val key = _uiState.value.searchKey.trim()
        val items = if (key.isEmpty()) {
            allItems
        } else {
            allItems.filter { it.matchesKey(key) }
        }
        _uiState.update { it.copy(items = items) }
    }

    fun load(mode: CacheManageMode = _uiState.value.mode) {
        loadJob?.cancel()
        sizeJob?.cancel()
        _uiState.update { it.copy(mode = mode, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                //第一阶段只拿书单与章节数（一次 group by），先把书名显示出来
                val items = withContext(Dispatchers.IO) { loadItems(mode) }
                allItems = items
                applyFilter()
                _uiState.update { it.copy(loading = false) }
                //第二阶段并行补"已缓存章节数 + 占用"，算完一批刷一批
                fillCacheInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("缓存管理加载失败 ${mode.name}\n${e.localizedMessage}", e)
                _uiState.update { it.copy(loading = false, error = e.localizedMessage) }
            }
        }
    }

    /**
     * 逐批并行补缓存概况（已缓存章节数 + 占用）
     *
     * 单本要遍历一遍缓存目录（大视频书上千个分片），串行算会让列表从第一本卡到最后一本；
     * 这里每批并行算，算完一批刷一批，书名在第一阶段就已经显示出来了
     */
    private fun fillCacheInfo() {
        sizeJob?.cancel()
        val mode = _uiState.value.mode
        sizeJob = viewModelScope.launch {
            allItems.filterNot { it.storageCalculated }
                .chunked(CACHE_INFO_BATCH_SIZE)
                .forEach { batch ->
                    if (_uiState.value.mode != mode) return@launch
                    val infos = withContext(Dispatchers.IO) {
                        coroutineScope {
                            batch.map { item ->
                                async { item.book.bookUrl to calcCacheInfo(item) }
                            }.awaitAll()
                        }
                    }
                    val byUrl = infos.toMap()
                    byUrl.forEach { (bookUrl, info) -> computedByBookUrl[bookUrl] = info }
                    allItems = allItems.map { item ->
                        val info = byUrl[item.book.bookUrl]
                        if (info == null) {
                            item
                        } else {
                            item.copy(
                                cachedCount = info.first,
                                storageSizeBytes = info.second,
                                storageCalculated = true
                            )
                        }
                    }
                    applyFilter()
                }
        }
    }

    /**
     * 单本书的缓存概况：已缓存章节数 + 目录占用
     *
     * 一次遍历目录同时得到两者：分开算会把同一棵目录树走两遍
     */
    private fun calcCacheInfo(item: CacheBookItem): Pair<Int, Long> {
        val book = item.book
        val dir = cacheDirOf(book, item.manifest)
        if (!dir.exists()) return 0 to 0L
        var size = 0L
        var chapterFiles = 0
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
                if (file.extension == "nb") chapterFiles++
            }
        }
        val cachedCount = if (item.mode.isMedia) {
            var count = countCachedMediaChapters(book, item.manifest)
            //清单功能之前缓存的老书地址不可知，只有一章时按缓存内容反推
            if (count == 0 && item.totalChapterCount == 1) {
                if (CacheManifestHelper.recoverLegacyMediaUrl(book) != null) {
                    count = countCachedMediaChapters(book, item.manifest)
                }
            }
            count
        } else {
            //文本/漫画一章一个 .nb 文件，直接数文件比逐章比对便宜得多
            chapterFiles
        }
        val total = item.totalChapterCount.takeIf { it > 0 } ?: cachedCount
        return cachedCount.coerceAtMost(total) to size
    }

    private suspend fun loadItems(mode: CacheManageMode): List<CacheBookItem> {
        val books = appDb.bookDao.all
            .filter { !it.isLocal && it.isType(mode.bookType) }
        val manifests = CacheManifestHelper.listManifests()
        //清单只读一遍，之后的逐本查询都走内存，避免每本书都去扫一遍缓存目录
        manifestCache = manifests.associateBy { it.bookUrl }
        //章节总数一次查全，不要逐本读章节表
        val chapterCounts = appDb.bookChapterDao.getChapterCounts()
            .associate { it.bookUrl to it.count }
        val items = books.mapTo(mutableListOf()) { book ->
            buildItem(book, mode, manifestCache[book.bookUrl], chapterCounts[book.bookUrl])
        }
        //缓存还在、书籍记录已删除的：靠清单列出来，卡片上提供"加入书架"
        val bookUrls = books.mapTo(hashSetOf()) { it.bookUrl }
        manifests.asSequence()
            .filter { !bookUrls.contains(it.bookUrl) }
            .filter { it.type and mode.bookType > 0 }
            .forEach { items.add(buildItemFromManifest(it, mode)) }
        return items.sortedByDescending { it.book.durChapterTime }
    }

    /**
     * 列表项只算"总章节数 + 已缓存章节数"，不把整张章节表读出来
     *
     * 大书动辄上千章，逐本读章节表再逐章对缓存，会让加载时间随书量线性变慢；
     * 章节明细留给章节弹窗按需加载
     */
    private fun buildItem(
        book: Book,
        mode: CacheManageMode,
        manifest: CacheBookManifest?,
        chapterCount: Int? = null
    ): CacheBookItem {
        val totalChapterCount = chapterCount?.takeIf { it > 0 }
            ?: appDb.bookChapterDao.getChapterCount(book.bookUrl).takeIf { it > 0 }
            ?: book.totalChapterNum
        //已经算过这本书就直接带上：切分类、重进页面不重复扫盘
        val known = computedByBookUrl[book.bookUrl]
        return CacheBookItem(
            book = book,
            mode = mode,
            cachedCount = known?.first ?: 0,
            totalChapterCount = totalChapterCount,
            storageSizeBytes = known?.second ?: 0L,
            storageCalculated = known != null,
            manifest = manifest,
            inBookshelf = !book.isNotShelf
        )
    }

    /**
     * 媒体：只核对清单里记过"已缓存"的那几章
     *
     * 清单里的缓存标记是上次刷新按同一口径写的，逐章查媒体缓存对大书同样很慢；
     * 没有清单的老缓存才退化成逐章核对
     */
    private fun countCachedMediaChapters(book: Book, manifest: CacheBookManifest?): Int {
        if (manifest == null) {
            return appDb.bookChapterDao.getChapterList(book.bookUrl)
                .filterNot { it.isVolume }
                .count { isMediaChapterCached(book, it) }
        }
        val candidate = manifest.cachedIndexes
        if (candidate.isEmpty()) return 0
        return manifest.chapters.count { recorded ->
            recorded.index in candidate && isMediaChapterCached(
                book,
                CacheManifestHelper.toChapter(recorded, book.bookUrl),
                manifest
            )
        }
    }

    private fun buildItemFromManifest(
        manifest: CacheBookManifest,
        mode: CacheManageMode
    ): CacheBookItem {
        val book = CacheManifestHelper.toBook(manifest)
        val chapters = CacheManifestHelper.toChapters(manifest)
        val cachedCount = if (mode.isMedia) {
            chapters.count { isMediaChapterCached(book, it, manifest) }
        } else {
            manifest.cachedChapterCount
        }
        return CacheBookItem(
            book = book,
            mode = mode,
            cachedCount = cachedCount,
            totalChapterCount = chapters.size.takeIf { it > 0 } ?: manifest.totalChapterNum,
            manifest = manifest,
            inBookshelf = false
        )
    }

    /**
     * 媒体章节是否已缓存
     *
     * 地址可能变过（章节表里存的是最近一次解析结果），所以判定交给"章节表地址 + 清单里的缓存时地址"
     */
    private fun isMediaChapterCached(
        book: Book,
        chapter: BookChapter,
        manifest: CacheBookManifest? = null
    ): Boolean {
        return CacheManifestHelper.cachedMediaUrl(
            book,
            chapter,
            manifest ?: findManifest(book)
        ) != null
    }

    // region 章节弹窗

    fun openChapterDialog(item: CacheBookItem) {
        _uiState.update {
            it.copy(chapterDialog = CacheChapterDialogState(book = item.book, loading = true))
        }
        loadChapters()
    }

    fun dismissChapterDialog() {
        chapterJob?.cancel()
        _uiState.update { it.copy(chapterDialog = null) }
    }

    fun searchChapters(key: String) {
        _uiState.update { state ->
            val dialog = state.chapterDialog ?: return@update state
            state.copy(chapterDialog = dialog.copy(key = key))
        }
        loadChapters()
    }

    fun switchChapterFilter(filter: CacheChapterFilter) {
        _uiState.update { state ->
            val dialog = state.chapterDialog ?: return@update state
            if (dialog.filter == filter) return@update state
            state.copy(
                chapterDialog = dialog.copy(
                    filter = filter,
                    selectedIndexes = emptySet(),
                    selectionMode = false
                )
            )
        }
        loadChapters()
    }

    private fun loadChapters() {
        val dialog = _uiState.value.chapterDialog ?: return
        chapterJob?.cancel()
        _uiState.update { state ->
            state.copy(chapterDialog = state.chapterDialog?.copy(loading = true))
        }
        chapterJob = viewModelScope.launch {
            try {
                if (dialog.key.isNotBlank()) {
                    delay(CHAPTER_SEARCH_DEBOUNCE_MS)
                }
                val items = withContext(Dispatchers.IO) {
                    loadChapterItems(dialog.book, dialog.key, dialog.filter)
                }
                _uiState.update { state ->
                    state.copy(
                        chapterDialog = state.chapterDialog?.copy(
                            chapters = items,
                            loading = false,
                            error = null
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("读取缓存章节失败 ${dialog.book.name}\n${e.localizedMessage}", e)
                //错误显示在弹窗里而不是只在 Toast：弹窗还开着时用户能看到原因与重试入口
                _uiState.update { state ->
                    state.copy(
                        chapterDialog = state.chapterDialog?.copy(
                            loading = false,
                            error = e.localizedMessage ?: appCtx.getString(R.string.error)
                        )
                    )
                }
            }
        }
    }

    private suspend fun loadChapterItems(
        book: Book,
        key: String,
        filter: CacheChapterFilter
    ): List<CacheChapterItem> {
        val manifest = findManifestDeep(book)
        val dbChapters = if (key.isBlank()) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            appDb.bookChapterDao.search(book.bookUrl, key)
        }
        //音视频章节的播放地址会被目录刷新清空，用清单里记的历史地址补回来，
        //否则已缓存的媒体会变成"判定不到缓存"的孤儿
        if (book.isMedia && CacheManifestHelper.mergeResourceUrls(book, dbChapters, manifest)) {
            appDb.bookChapterDao.update(*dbChapters.toTypedArray())
        }
        val chapters = dbChapters.takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.toChapters(manifest ?: return emptyList())
                .filterByKey(key)
        val cacheNames = if (book.isMedia) {
            emptySet()
        } else {
            cacheDirOf(book).list()?.toSet().orEmpty()
        }

        return chapters.asSequence()
            .filterNot { it.isVolume }
            .map { chapter ->
                val cached = if (book.isMedia) {
                    isMediaChapterCached(book, chapter, manifest)
                } else {
                    //标题/序号被目录刷新改过时，按清单里缓存当时的名字找回
                    CacheManifestHelper.cachedTextFileName(book, chapter, manifest, cacheNames) != null
                }
                CacheChapterItem(chapter = chapter, cached = cached)
            }
            .toList()
            .applyChapterFilter(filter)
    }

    fun toggleChapterSelection(chapter: BookChapter) {
        _uiState.update { state ->
            val dialog = state.chapterDialog ?: return@update state
            val selected = dialog.selectedIndexes.toMutableSet()
            if (!selected.add(chapter.index)) {
                selected.remove(chapter.index)
            }
            state.copy(
                chapterDialog = dialog.copy(
                    selectedIndexes = selected,
                    selectionMode = true
                )
            )
        }
    }

    fun selectAllVisibleChapters() {
        _uiState.update { state ->
            val dialog = state.chapterDialog ?: return@update state
            state.copy(
                chapterDialog = dialog.copy(
                    selectedIndexes = dialog.chapters.mapTo(hashSetOf()) { it.chapter.index },
                    selectionMode = true
                )
            )
        }
    }

    fun clearChapterSelection() {
        _uiState.update { state ->
            val dialog = state.chapterDialog ?: return@update state
            state.copy(
                chapterDialog = dialog.copy(selectedIndexes = emptySet(), selectionMode = false)
            )
        }
    }

    /**
     * 缓存所选章节：加入缓存任务队列（未缓存的才入队）
     */
    fun cacheSelectedChapters() {
        val dialog = _uiState.value.chapterDialog ?: return
        val chapters = dialog.chapters
            .filter { dialog.selectedIndexes.contains(it.chapter.index) && !it.cached }
            .map { it.chapter }
        if (chapters.isEmpty()) {
            toast(R.string.cache_manage_batch_empty)
            return
        }
        val count = cacheTaskStarter.start(dialog.book, chapters)
        if (count <= 0) {
            toast(R.string.cache_manage_batch_empty)
            return
        }
        toast(R.string.cache_manage_cache_selected_done, count)
        clearChapterSelection()
        //缓存任务在后台跑，事件不保证覆盖媒体下载的每个阶段，这里兜底轮询到本行数据落定
        pollItemUntilSettled(dialog.book.bookUrl)
        //媒体缓存是后台排队下载，进度由缓存任务通知/事件反映，这里关掉弹窗避免误以为没反应
        if (dialog.book.isMedia) {
            dismissChapterDialog()
        } else {
            loadChapters()
        }
    }

    /**
     * 删除所选章节的缓存（只删已缓存的）
     */
    fun deleteSelectedChapters() {
        val dialog = _uiState.value.chapterDialog ?: return
        val chapters = dialog.chapters
            .filter { dialog.selectedIndexes.contains(it.chapter.index) && it.cached }
            .map { it.chapter }
        if (chapters.isEmpty()) {
            toast(R.string.cache_manage_batch_empty)
            return
        }
        _uiState.update { it.copy(confirm = CacheManageConfirm.DeleteChapters(dialog.book, chapters)) }
    }

    // endregion

    // region 书籍级操作

    /**
     * 用缓存打开某章：书不在书架时先按清单恢复书架与章节，再跳转阅读/播放
     */
    fun openCachedChapter(book: Book, chapter: BookChapter) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                var target = appDb.bookDao.getBook(book.bookUrl)
                if (target == null) {
                    //只有缓存、书已删除：先用清单把书与章节恢复回来
                    if (!restoreToBookshelf(book)) return@withContext null
                    target = appDb.bookDao.getBook(book.bookUrl)
                }
                target?.let {
                    it.durChapterIndex = chapter.index
                    it.durChapterTitle = chapter.title
                    it.durChapterPos = 0
                    appDb.bookDao.update(it)
                }
                target
            } ?: return@launch
            _events.send(CacheManageEvent.OpenChapter(target, chapter))
        }
    }

    /**
     * 查找书籍的缓存清单：先按书籍缓存目录找，书改名/换源导致目录名与当前书名不一致时按 bookUrl 兜底
     */
    private fun findManifest(book: Book): CacheBookManifest? {
        manifestCache[book.bookUrl]?.let { return it }
        return CacheManifestHelper.read(book)
    }

    /** 按 bookUrl 兜底找清单（书改过名、目录名与书名不一致时用）：只用于单次操作，不进列表热路径 */
    private fun findManifestDeep(book: Book): CacheBookManifest? {
        return findManifest(book)
            ?: CacheManifestHelper.listManifests().firstOrNull { it.bookUrl == book.bookUrl }
    }

    /**
     * 书籍缓存目录
     *
     * 优先按清单里的 folderName 取：书改过名时按当前书名算出的目录名与磁盘上的目录对不上，
     * 会算成 0 占用、打不出缓存包，也删不掉缓存
     */
    private fun cacheDirOf(book: Book, manifest: CacheBookManifest? = null): File {
        val folderName = (manifest ?: findManifest(book))?.folderName
        if (!folderName.isNullOrBlank()) {
            val dir = File(BookHelp.cachePath, folderName)
            if (dir.exists()) return dir
        }
        return BookHelp.getCacheDir(book)
    }

    /**
     * 按清单把"只剩缓存"的书恢复进书架：书籍信息与章节都取自清单，章节保留原地址以便直接用缓存
     *
     * 只用于书已不在书架的场景。书还在书架时不要走这里——清单是某次刷新时的章节快照，
     * 拿它整表覆盖会把用户后来新增/调整过的章节回滚掉
     */
    private fun restoreFromManifest(manifest: CacheBookManifest): Boolean {
        return runCatching {
            val sameUrlBook = appDb.bookDao.getBook(manifest.bookUrl)
            val sameNameBook = appDb.bookDao.getBook(manifest.name, manifest.author)
            val cacheBook = CacheManifestHelper.toBook(manifest).apply {
                removeType(BookType.notShelf)
                val source = sameUrlBook ?: sameNameBook
                source?.let {
                    group = it.group
                    order = it.order
                    durChapterIndex = it.durChapterIndex
                    durChapterTitle = it.durChapterTitle
                    durChapterPos = it.durChapterPos
                    readConfig = it.readConfig
                }
            }
            when {
                sameUrlBook != null -> appDb.bookDao.update(cacheBook)
                sameNameBook != null -> appDb.bookDao.replace(sameNameBook, cacheBook)
                else -> appDb.bookDao.insert(cacheBook)
            }
            val chapters = CacheManifestHelper.toChapters(manifest, cacheBook.bookUrl)
            if (chapters.isNotEmpty()) {
                appDb.bookChapterDao.delByBook(cacheBook.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
            true
        }.onFailure {
            AppLog.put("从缓存恢复书籍失败 ${manifest.name}\n${it.localizedMessage}", it)
        }.getOrDefault(false)
    }

    /**
     * "使用缓存"：书还在书架时把清单里的媒体地址同步给现有章节（音视频靠它才认得已缓存的媒体），
     * 书已不在书架时才按清单整本恢复回来
     *
     * @return 书架中的书；没有可用的缓存信息时返回 null
     */
    private fun useCache(item: CacheBookItem): Book? {
        val dbBook = appDb.bookDao.getBook(item.book.bookUrl)
        val manifest = findManifestDeep(item.book)
        if (dbBook != null) {
            //章节表结构保持不动，只把媒体地址对齐成"确实有缓存的那个"：
            //只补空值救不回"地址被新解析结果覆盖"的情况，那正是缓存读不到的原因
            if (manifest != null) {
                val chapters = appDb.bookChapterDao.getChapterList(dbBook.bookUrl)
                if (CacheManifestHelper.mergeResourceUrls(dbBook, chapters, manifest)) {
                    appDb.bookChapterDao.update(*chapters.toTypedArray())
                }
            }
            return dbBook
        }
        val restored = manifest?.let { restoreFromManifest(it) } ?: false
        return if (restored) appDb.bookDao.getBook(item.book.bookUrl) else null
    }

    /**
     * 用缓存打开章节：书不在书架时先按清单把它恢复回书架
     */
    private fun restoreToBookshelf(book: Book): Boolean {
        val manifest = findManifestDeep(book) ?: return false
        return restoreFromManifest(manifest)
    }

    fun requestRestoreToBookshelf(item: CacheBookItem) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) { useCache(item) }
            if (target == null) {
                //只有"书已不在书架、又没有清单"才会走到这里：无法凭缓存把书恢复出来
                toast(R.string.cache_manage_use_cache_failed)
                return@launch
            }
            toast(
                if (item.inBookshelf) {
                    R.string.cache_manage_use_cache_success
                } else {
                    R.string.cache_manage_add_bookshelf_success
                }
            )
            load()
        }
    }

    fun requestDeleteBookCache(item: CacheBookItem) {
        _uiState.update { it.copy(confirm = CacheManageConfirm.DeleteBook(item.book)) }
    }

    fun requestDeleteAll() {
        val books = _uiState.value.items.filter { it.cachedCount > 0 }.map { it.book }
        if (books.isEmpty()) {
            toast(R.string.cache_manage_batch_empty)
            return
        }
        _uiState.update { it.copy(confirm = CacheManageConfirm.DeleteAll(books)) }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirm = null) }
    }

    /**
     * 确认框里的操作统一在这里执行
     */
    fun confirmAction() {
        val confirm = _uiState.value.confirm ?: return
        _uiState.update { it.copy(confirm = null) }
        when (confirm) {
            is CacheManageConfirm.DeleteChapters -> deleteChapterCaches(confirm.book, confirm.chapters)
            is CacheManageConfirm.DeleteBook -> deleteBookCaches(listOf(confirm.book))
            is CacheManageConfirm.DeleteAll -> deleteBookCaches(confirm.books)
        }
    }

    private fun deleteChapterCaches(book: Book, chapters: List<BookChapter>) {
        viewModelScope.launch {
            _uiState.update { it.copy(working = true) }
            try {
                withContext(Dispatchers.IO) {
                    chapters.forEach { chapter ->
                        if (book.isMedia) {
                            ExoPlayerHelper.removeMediaCache(
                                url = chapter.resourceUrl,
                                book = book,
                                useVideoCache = book.isVideo
                            )
                        } else {
                            BookHelp.delContent(book, chapter)
                        }
                    }
                    CacheManifestHelper.refresh(book)
                }
                toast(R.string.delete_success)
                loadChapters()
                refreshCurrentItem(book)
            } catch (e: Exception) {
                AppLog.put("删除章节缓存失败 ${book.name}\n${e.localizedMessage}", e)
                toast(R.string.cache_manage_delete_chapter_failed, e.localizedMessage ?: "")
            } finally {
                _uiState.update { it.copy(working = false) }
            }
        }
    }

    /**
     * 删除整本书的缓存目录（含媒体与缓存清单）
     *
     * 目录名以清单里的 folderName 为准：书改过名时按当前书名算出的目录名对不上，
     * 会删不掉缓存
     */
    private fun clearBookCache(book: Book) {
        CacheBook.cacheBookMap[book.bookUrl]?.stop()
        val cacheDir = cacheDirOf(book, findManifestDeep(book))
        if (cacheDir.exists()) {
            ExoPlayerHelper.releaseBookMediaCacheOf(cacheDir)
            FileUtils.delete(cacheDir.absolutePath)
        } else {
            BookHelp.clearCache(book)
        }
    }

    private fun deleteBookCaches(books: List<Book>) {
        viewModelScope.launch {
            _uiState.update { it.copy(working = true) }
            try {
                withContext(Dispatchers.IO) {
                    books.forEach { book -> clearBookCache(book) }
                }
                //概况会喂给重建后的列表当"已算过"的初值，不丢掉的话删完还显示删除前的数字
                books.forEach { computedByBookUrl.remove(it.bookUrl) }
                toast(R.string.delete_success)
                load()
            } catch (e: Exception) {
                AppLog.put("删除书籍缓存失败\n${e.localizedMessage}", e)
                toast(R.string.cache_manage_delete_failed, e.localizedMessage ?: "")
            } finally {
                _uiState.update { it.copy(working = false) }
            }
        }
    }

    /**
     * 把一本书的缓存目录打包上传到 WebDAV
     */
    fun uploadBookCache(item: CacheBookItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(working = true) }
            toast(R.string.cache_manage_uploading)
            try {
                val zipFile = withContext(Dispatchers.IO) { createCachePackage(item.book) }
                withContext(Dispatchers.IO) {
                    AppWebDav.uploadCachePackage(zipFile.name, zipFile)
                }
                toast(R.string.cache_manage_upload_success)
            } catch (e: Exception) {
                AppLog.put("上传缓存失败 ${item.book.name}\n${e.localizedMessage}", e)
                toast(R.string.cache_manage_upload_failed, e.localizedMessage ?: "")
            } finally {
                _uiState.update { it.copy(working = false) }
            }
        }
    }

    fun uploadAllCaches() {
        val items = _uiState.value.items.filter { it.cachedCount > 0 }
        if (items.isEmpty()) {
            toast(R.string.cache_manage_batch_empty)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(working = true) }
            toast(R.string.cache_manage_uploading)
            var success = 0
            var failed = 0
            items.forEach { item ->
                runCatching {
                    val zipFile = withContext(Dispatchers.IO) { createCachePackage(item.book) }
                    withContext(Dispatchers.IO) {
                        AppWebDav.uploadCachePackage(zipFile.name, zipFile)
                    }
                }.onSuccess {
                    success++
                }.onFailure { e ->
                    failed++
                    AppLog.put("上传缓存失败 ${item.book.name}\n${e.localizedMessage}", e)
                }
            }
            _uiState.update { it.copy(working = false) }
            toast(R.string.cache_manage_batch_upload_done, success, failed)
        }
    }

    /**
     * 打包整本书的缓存目录（含 cache_manifest.json 与媒体目录），压缩包解压即还原缓存
     */
    private fun createCachePackage(book: Book): File {
        val cacheDir = cacheDirOf(book)
        if (!cacheDir.exists() || cacheDir.listFiles().isNullOrEmpty()) {
            throw IllegalStateException(appCtx.getString(R.string.cache_manage_no_cache))
        }
        val outDir = File(appCtx.externalCache, CACHE_PACKAGE_DIR).apply { mkdirs() }
        val fileName = "${book.name}_${book.author}_${System.currentTimeMillis()}"
            .normalizeFileName()
            .ifBlank { "cache_${System.currentTimeMillis()}" }
        val zipFile = File(outDir, "$fileName.zip")
        if (zipFile.exists()) zipFile.delete()
        if (!ZipUtils.zipFile(cacheDir, zipFile)) {
            throw IllegalStateException(appCtx.getString(R.string.cache_manage_pack_failed))
        }
        return zipFile
    }

    // endregion

    /**
     * 别处把缓存删了（统计页清理、系统清理）后作废内存里算好的概况
     *
     * 概况是列表重建时的初值，不清掉的话下次进分类会拿删除前的数字当"已算过"直接显示；
     * 这里只清内存不重扫，回到按书分类时自然重算
     */
    fun invalidateCacheInfo() {
        computedByBookUrl.clear()
    }

    /**
     * 缓存任务有进度或结束时重算那一行
     *
     * 下载是后台服务在做，界面拿不到结束时机；不跟着刷新的话，缓存下完界面还停在"已缓存 0/1"。
     * 同一本书的进度事件每 500ms 就来一次，按书节流后再重算
     */
    fun refreshItem(bookUrl: String) {
        if (bookUrl.isBlank()) return
        if (allItems.none { it.book.bookUrl == bookUrl }) return
        val now = System.currentTimeMillis()
        if (now - (itemRefreshAt[bookUrl] ?: 0L) < ITEM_REFRESH_INTERVAL_MS) return
        itemRefreshAt[bookUrl] = now
        viewModelScope.launch {
            val book = withContext(Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) } ?: return@launch
            refreshCurrentItem(book)
        }
    }

    /**
     * 入队缓存后按固定间隔重算这一行，直到这本书的章节都缓存完或超出重试次数
     *
     * 缓存任务在后台服务里执行，事件只覆盖部分阶段（媒体下载完成就不一定发事件），
     * 不兜底的话界面会一直停在缓存前的数字
     */
    private fun pollItemUntilSettled(bookUrl: String) {
        viewModelScope.launch {
            repeat(ITEM_POLL_ATTEMPTS) {
                delay(ITEM_POLL_INTERVAL_MS)
                val item = allItems.firstOrNull { it.book.bookUrl == bookUrl }
                    ?: return@launch
                if (item.cachedCount >= item.totalChapterCount) return@launch
                val book = withContext(Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) }
                    ?: return@launch
                refreshCurrentItem(book)
            }
        }
    }

    private fun refreshCurrentItem(book: Book) {
        val mode = _uiState.value.mode
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val item = buildItem(book, mode, CacheManifestHelper.read(book))
                val info = calcCacheInfo(item)
                computedByBookUrl[book.bookUrl] = info
                item.copy(
                    cachedCount = info.first,
                    storageSizeBytes = info.second,
                    storageCalculated = true
                )
            }
            //全量列表换掉这一行，再按当前搜索关键字重新过滤
            allItems = allItems.map { if (it.book.bookUrl == book.bookUrl) fresh else it }
            applyFilter()
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun toast(resId: Int, vararg args: Any) {
        _events.trySend(CacheManageEvent.Toast(resId, args.toList()))
    }

    companion object {
        private const val CHAPTER_SEARCH_DEBOUNCE_MS = 180L
        private const val CACHE_PACKAGE_DIR = "cache_package"

        /** 缓存任务进度事件的单行重算间隔 */
        private const val ITEM_REFRESH_INTERVAL_MS = 500L

        /** 每批并行计算缓存概况的书本数：太小并行不起来，太大同时开太多目录遍历 */
        private const val CACHE_INFO_BATCH_SIZE = 4

        /** 入队缓存后的兜底轮询间隔与次数（3s × 20 = 1 分钟内落定） */
        private const val ITEM_POLL_INTERVAL_MS = 3_000L
        private const val ITEM_POLL_ATTEMPTS = 20

        /**
         * 默认工厂用于预览/测试：不启动真实缓存任务
         */
        val Factory = Factory()

        fun Factory(
            cacheTaskStarter: CacheTaskStarter = CacheTaskStarter { _, _ -> 0 }
        ) = viewModelFactory {
            initializer { CacheManageViewModel(cacheTaskStarter) }
        }
    }
}

/**
 * 缓存体积格式化（列表里展示，与详情的格式化口径一致）
 */
fun Long.toCacheSizeText(): String = ConvertUtils.formatFileSize(this)

/**
 * 递归统计目录占用：书籍缓存目录里同时有章节文件、媒体目录与清单，逐层累加
 */
private fun File.directorySize(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    return listFiles()?.sumOf { it.directorySize() } ?: 0L
}
