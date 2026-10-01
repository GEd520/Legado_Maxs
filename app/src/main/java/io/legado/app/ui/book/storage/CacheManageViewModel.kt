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
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

    fun load(mode: CacheManageMode = _uiState.value.mode) {
        loadJob?.cancel()
        sizeJob?.cancel()
        _uiState.update { it.copy(mode = mode, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { loadItems(mode) }
                _uiState.update { it.copy(items = items, loading = false) }
                calculateItemSizes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("缓存管理加载失败 ${mode.name}\n${e.localizedMessage}", e)
                _uiState.update { it.copy(loading = false, error = e.localizedMessage) }
            }
        }
    }

    /**
     * 各书缓存体积要递归扫目录，加载完列表后逐本补上，避免整页等一次大扫描
     */
    private fun calculateItemSizes() {
        sizeJob?.cancel()
        val mode = _uiState.value.mode
        sizeJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _uiState.value.items.forEach { item ->
                    if (_uiState.value.mode != mode) return@withContext
                    val size = BookHelp.getCacheDir(item.book).directorySize()
                    _uiState.update { state ->
                        state.copy(
                            items = state.items.map {
                                if (it.book.bookUrl == item.book.bookUrl) {
                                    it.copy(storageSizeBytes = size, storageCalculated = true)
                                } else {
                                    it
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadItems(mode: CacheManageMode): List<CacheBookItem> {
        val books = appDb.bookDao.all
            .filter { !it.isLocal && it.isType(mode.bookType) }
        val manifests = CacheManifestHelper.listManifests()
        val manifestByBookUrl = manifests.associateBy { it.bookUrl }
        val items = books.mapTo(mutableListOf()) { book ->
            buildItem(book, mode, manifestByBookUrl[book.bookUrl])
        }
        //缓存还在、书籍记录已删除的：靠清单列出来，卡片上提供"加入书架"
        val bookUrls = books.mapTo(hashSetOf()) { it.bookUrl }
        manifests.asSequence()
            .filter { !bookUrls.contains(it.bookUrl) }
            .filter { it.type and mode.bookType > 0 }
            .forEach { items.add(buildItemFromManifest(it, mode)) }
        return items.sortedByDescending { it.book.durChapterTime }
    }

    private fun buildItem(
        book: Book,
        mode: CacheManageMode,
        manifest: CacheBookManifest?
    ): CacheBookItem {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val realChapters = chapters.filterNot { it.isVolume }
        val cachedCount = if (mode.isMedia) {
            realChapters.count { isMediaChapterCached(book, it) }
        } else {
            val cacheNames = BookHelp.getCacheDir(book).list()?.toSet().orEmpty()
            realChapters.count { cacheNames.contains(it.getFileName()) }
        }
        return CacheBookItem(
            book = book,
            mode = mode,
            cachedCount = cachedCount,
            totalChapterCount = realChapters.size.takeIf { it > 0 } ?: book.totalChapterNum,
            manifest = manifest,
            inBookshelf = !book.isNotShelf
        )
    }

    private fun buildItemFromManifest(
        manifest: CacheBookManifest,
        mode: CacheManageMode
    ): CacheBookItem {
        val book = CacheManifestHelper.toBook(manifest)
        val chapters = CacheManifestHelper.toChapters(manifest)
        val cachedCount = if (mode.isMedia) {
            chapters.count { isMediaChapterCached(book, it) }
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

    private fun isMediaChapterCached(book: Book, chapter: BookChapter): Boolean {
        return if (book.isVideo) {
            ExoPlayerHelper.isVideoCached(chapter.resourceUrl, book)
        } else {
            ExoPlayerHelper.isMediaCached(chapter.resourceUrl, book)
        }
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

    /**
     * 读取缓存清单
     *
     * 先按书籍缓存目录找；书改名/换源导致目录名与当前书名不一致时，再按 bookUrl 扫一遍——
     * 清单里的 folderName 才是缓存目录的权威来源，重算目录名未必对得上
     */
    private suspend fun readManifest(book: Book): CacheBookManifest? {
        CacheManifestHelper.read(book)?.let { return it }
        return withContext(Dispatchers.IO) {
            CacheManifestHelper.listManifests().firstOrNull { it.bookUrl == book.bookUrl }
        }
    }

    private suspend fun loadChapterItems(
        book: Book,
        key: String,
        filter: CacheChapterFilter
    ): List<CacheChapterItem> {
        val manifest = readManifest(book)
        val dbChapters = if (key.isBlank()) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            appDb.bookChapterDao.search(book.bookUrl, key)
        }
        //音视频章节的播放地址会被目录刷新清空，用清单里记的历史地址补回来，
        //否则已缓存的媒体会变成"判定不到缓存"的孤儿
        if (book.isMedia && CacheManifestHelper.mergeResourceUrls(dbChapters, manifest)) {
            appDb.bookChapterDao.update(*dbChapters.toTypedArray())
        }
        val chapters = dbChapters.takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.toChapters(manifest ?: return emptyList())
                .filterByKey(key)
        val cacheNames = if (book.isMedia) {
            emptySet()
        } else {
            BookHelp.getCacheDir(book).list()?.toSet().orEmpty()
        }

        return chapters.asSequence()
            .filterNot { it.isVolume }
            .map { chapter ->
                val cached = if (book.isMedia) {
                    isMediaChapterCached(book, chapter)
                } else {
                    cacheNames.contains(chapter.getFileName())
                }
                CacheChapterItem(chapter = chapter, cached = cached)
            }
            .filter {
                when (filter) {
                    CacheChapterFilter.ALL -> true
                    CacheChapterFilter.CACHED -> it.cached
                    CacheChapterFilter.UNCACHED -> !it.cached
                }
            }
            .toList()
    }

    private fun List<BookChapter>.filterByKey(key: String): List<BookChapter> {
        if (key.isBlank()) return this
        return filter { it.title.contains(key, true) }
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
     * 把"只剩缓存"的书加回书架：书籍信息与章节都取自缓存清单，章节保留原地址以便直接用缓存
     * @return 是否成功
     */
    fun restoreToBookshelf(book: Book): Boolean {
        return runCatching {
            //目录名可能与当前书名不一致（书改过名），按 bookUrl 兜底找一遍
            val manifest = CacheManifestHelper.read(book)
                ?: CacheManifestHelper.listManifests().firstOrNull { it.bookUrl == book.bookUrl }
                ?: return false
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
            AppLog.put("恢复缓存到书架失败 ${book.name}\n${it.localizedMessage}", it)
        }.getOrDefault(false)
    }

    fun requestRestoreToBookshelf(item: CacheBookItem) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                if (restoreToBookshelf(item.book)) appDb.bookDao.getBook(item.book.bookUrl) else null
            }
            if (target == null) {
                toast(R.string.cache_manage_no_cache)
                return@launch
            }
            //书本来就在书架时是"章节按缓存里的记录对齐"，否则是"从缓存加回书架"
            toast(if (item.inBookshelf) R.string.cache_manage_use_cache_success else R.string.cache_manage_add_bookshelf_success)
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

    private fun deleteBookCaches(books: List<Book>) {
        viewModelScope.launch {
            _uiState.update { it.copy(working = true) }
            try {
                withContext(Dispatchers.IO) {
                    books.forEach { book ->
                        ExoPlayerHelper.releaseBookCaches(book)
                        //BookHelp.clearCache 会先停缓存任务再删整本缓存目录（含媒体与清单）
                        BookHelp.clearCache(book)
                    }
                }
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
        val cacheDir = BookHelp.getCacheDir(book)
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

    private fun refreshCurrentItem(book: Book) {
        val mode = _uiState.value.mode
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { buildItem(book, mode, CacheManifestHelper.read(book)) }
            _uiState.update { state ->
                state.copy(
                    items = state.items.map {
                        if (it.book.bookUrl == book.bookUrl) {
                            item.copy(
                                storageSizeBytes = BookHelp.getCacheDir(book).directorySize(),
                                storageCalculated = true
                            )
                        } else {
                            it
                        }
                    }
                )
            }
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
