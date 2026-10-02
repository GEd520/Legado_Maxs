package io.legado.app.ui.book.explore.compose

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.ui.book.explore.ExploreShowViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx

/** 列表布局模式：列表 → 网格 → 瀑布流 三轮换 */
const val EXPLORE_LAYOUT_LIST = 0
const val EXPLORE_LAYOUT_GRID = 1
const val EXPLORE_LAYOUT_WATERFALL = 2

/**
 * 页面初始化参数（从 Activity intent 与按书源持久化的配置读取）。
 *
 * @param exploreName 顶栏初始标题（intent 的 exploreName）
 * @param exploreUrl 初始分类 URL，用于在分类列表中定位选中项
 */
data class ExploreShowInitArgs(
    val exploreName: String,
    val exploreUrl: String,
    val layoutMode: Int,
    val columnGrid: Int,
    val columnWaterfall: Int,
    val showCategoryTab: Boolean,
    val preloadMode: Int,
    val showBlockProgress: Boolean,
)

/**
 * 平台操作与持久化回调集合（Screen 与 Controller 共用，平台调用全部收敛到 Activity）。
 */
class ExploreShowActions(
    val onBackClick: () -> Unit,
    /** 跳页选择弹窗：参数为当前页码与选中回调 */
    val onPagePick: (current: Int, onPicked: (Int) -> Unit) -> Unit,
    /** 列数选择弹窗：参数为当前列数与选中回调 */
    val onColumnPick: (current: Int, onPicked: (Int) -> Unit) -> Unit,
    val onAddAllToShelfClick: () -> Unit,
    val onShowBlockRuleClick: () -> Unit,
    val onShowBookInfo: (SearchBook) -> Unit,
    val addToShelf: (SearchBook) -> Unit,
    /** 分类区书源规则求值失败时弹出错误详情（仅新版发现的分类区使用） */
    val onShowError: (String) -> Unit = {},
    /** usehtml 内容里的图片长按查看（仅新版发现的分类区使用） */
    val onShowPhoto: (url: String, sourceUrl: String) -> Unit = { _, _ -> },
    val persistLayoutMode: (Int) -> Unit,
    val persistColumnGrid: (Int) -> Unit,
    val persistColumnWaterfall: (Int) -> Unit,
    val persistShowCategoryTab: (Boolean) -> Unit,
    val persistPreloadMode: (Int) -> Unit,
    val persistShowBlockProgress: (Boolean) -> Unit,
)

/** 加载更多 footer 状态（对齐 View 版 LoadMoreView 的语义） */
data class ExploreLoadMoreState(
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val message: String? = null,
    val isError: Boolean = false,
    /** 顶部翻页 footer 用，bottom 恒为 true */
    val visible: Boolean = true,
)

/** 滚动位置快照（首个可见项下标 + 偏移） */
data class ExploreScrollSnapshot(val index: Int, val offset: Int)

/** 内容区滚动指令，由 Controller 发出、列表内容消费 */
sealed interface ExploreScrollCommand {
    /** 跳页后列表重置：对齐 View 版 scrollToPositionWithOffset(1, 0)（仅列表模式执行） */
    data object SkipPageTop : ExploreScrollCommand

    /** 数据就位后按分类 URL 恢复缓存中的滚动位置 */
    data class RestoreCategory(val url: String?) : ExploreScrollCommand

    /** 顶部插入上一页数据后保持可见位置（仅列表模式执行） */
    data class PrependAnchor(val anchorIndex: Int) : ExploreScrollCommand

    /** 切换布局/列数后恢复首个可见项 */
    data class RestoreIndex(val index: Int) : ExploreScrollCommand
}

/**
 * 发现列表页状态持有者。
 *
 * 收拢 View 版 ExploreShowActivity 里的列表状态机：双向翻页、加载冷却、
 * 分类切换（含预加载）、按分类缓存滚动位置、布局三轮换。
 * LiveData → 本类的桥接由 Activity 在 onActivityCreated 中完成；
 * Screen 只读取这里的 Compose 状态并回调 [ExploreShowActions]。
 *
 * @param scope 冷却重试用的协程作用域（viewModelScope）
 */
class ExploreShowController(
    private val viewModel: ExploreShowViewModel,
    private val actions: ExploreShowActions,
    initialArgs: ExploreShowInitArgs,
    private val scope: CoroutineScope,
) {
    companion object {
        /** 加载下一页的冷却间隔（毫秒），列数 >3 的网格/瀑布流滚动过快时隔 2 秒再请求 */
        const val LOAD_COOLDOWN_MS = 2000L
        private const val MAX_COLUMN_COUNT = 10
    }

    // ── 按书源持久化的配置 ──

    var layoutMode by mutableIntStateOf(initialArgs.layoutMode)
        private set
    var columnGrid by mutableIntStateOf(initialArgs.columnGrid)
        private set
    var columnWaterfall by mutableIntStateOf(initialArgs.columnWaterfall)
        private set
    var showCategoryTab by mutableStateOf(initialArgs.showCategoryTab)
        private set
    var preloadMode by mutableIntStateOf(initialArgs.preloadMode)
        private set
    var showBlockProgress by mutableStateOf(initialArgs.showBlockProgress)
        private set
    var blockedCount by mutableIntStateOf(0)
        private set

    // ── 页面数据与 UI 状态 ──

    var books by mutableStateOf<List<SearchBook>>(emptyList())
        private set

    /** 条目稳定 key：首次出现序号按 origin+bookUrl 计数，前插/追加都不使旧 key 失效 */
    var bookKeys by mutableStateOf<List<String>>(emptyList())
        private set
    var kinds by mutableStateOf<List<ExploreKind>>(emptyList())
        private set
    var currentCategoryIndex by mutableIntStateOf(0)
        private set
    var pageTitle by mutableStateOf(initialArgs.exploreName)
        private set

    /** 当前加载的分类 URL（新版发现据此恢复标签选中态） */
    var currentExploreUrl by mutableStateOf<String?>(null)
        private set
    var currentPage by mutableIntStateOf(1)
        private set

    /** 书架状态刷新信号：BookshelfMatcher 变化后自增，触发条目重查书架状态 */
    var shelfTick by mutableIntStateOf(0)
        private set
    var footer by mutableStateOf(ExploreLoadMoreState(isLoading = true))
        private set
    var topFooter by mutableStateOf(ExploreLoadMoreState(visible = false))
        private set
    var scrollCommand by mutableStateOf<ExploreScrollCommand?>(null)
        private set

    private val initialExploreUrl = initialArgs.exploreUrl
    private var oldPage by mutableIntStateOf(-1)
    private var clearAllPending = false
    private var restorePendingUrl: String? = null
    private var lastLoadTime = 0L
    private var retryScheduled = false

    /** 最近一次视口上报的首个可见项（分类切换时保存滚动位置用） */
    private var lastFirstVisibleIndex = 0
    private var lastFirstVisibleOffset = 0
    private var prevViewportFirstIndex = -1
    private var prevViewportFirstOffset = -1

    private val scrollPositionCache = mutableMapOf<String, ExploreScrollSnapshot>()

    val booksCount: Int get() = books.size
    val canSwitchPreviousCategory: Boolean get() = currentCategoryIndex > 0
    val canSwitchNextCategory: Boolean get() = currentCategoryIndex < kinds.size - 1

    /** 当前布局模式的列数 */
    fun effectiveColumnCount(): Int =
        if (layoutMode == EXPLORE_LAYOUT_WATERFALL) columnWaterfall else columnGrid

    fun getBookShelfState(book: SearchBook) = viewModel.getBookShelfState(book)

    /** books 的唯一写入口：同步重建条目稳定 key */
    private fun updateBooks(newBooks: List<SearchBook>) {
        books = newBooks
        val seen = HashMap<String, Int>()
        bookKeys = newBooks.map { book ->
            val key = "${book.origin}_${book.bookUrl}"
            "${key}__${seen.merge(key, 1, Int::plus)}"
        }
    }

    /** 按下标取条目稳定 key（供 Lazy 列表 items 的 key 使用） */
    fun bookKey(index: Int): String = bookKeys.getOrElse(index) { "b_$index" }

    /** 读取指定分类缓存中的滚动位置快照 */
    fun cachedScrollSnapshot(url: String?): ExploreScrollSnapshot? =
        url?.let { scrollPositionCache[it] }

    /** 重新解析当前书源的分类并重载当前分类（新版发现三点菜单的"刷新"用） */
    fun refreshCurrent() = viewModel.refreshCurrent()

    /** Activity onDestroy 时清理缓存，避免内存泄漏（对齐 View 版） */
    fun clearCaches() {
        scrollPositionCache.clear()
        viewModel.clearPreloadCache()
    }

    // ── Activity LiveData 桥接 ──

    /**
     * 分类列表到达。
     * 与 View 版一致：每次到达都按初始 exploreUrl 重新定位选中项
     * （正常流程只在 initData 时加载一次）。
     */
    fun onKindsLoaded(loaded: List<ExploreKind>) {
        kinds = loaded
        currentCategoryIndex = loaded.indexOfFirst { it.url == initialExploreUrl }.coerceAtLeast(0)
    }

    fun onPageChanged(page: Int) {
        currentPage = page
    }

    fun onShelfStateChanged() {
        shelfTick++
    }

    fun onBlockedCountChanged(count: Int) {
        blockedCount = count
    }

    /** 下一页数据到达（对齐 View 版 upData 的增量语义） */
    fun upData(loaded: List<SearchBook>) {
        footer = footer.copy(isLoading = false)
        if (loaded.isEmpty() && books.isEmpty()) {
            footer = ExploreLoadMoreState(message = appCtx.getString(R.string.empty))
        } else if (books.size == loaded.size) {
            // 书源没有返回新增数据（整页被屏蔽等），显示到底
            footer = ExploreLoadMoreState(hasMore = false)
        } else {
            val oldCount = books.size
            if (oldCount == 0) {
                updateBooks(loaded)
                if (clearAllPending) {
                    // 跳页场景：落回列表顶部（restore 会被跳页目标覆盖）
                    clearAllPending = false
                    scrollCommand = ExploreScrollCommand.SkipPageTop
                } else {
                    restorePendingUrl = currentKindUrl()
                    scrollCommand = ExploreScrollCommand.RestoreCategory(restorePendingUrl)
                }
            } else if (loaded.size > oldCount) {
                updateBooks(books + loaded.subList(oldCount, loaded.size))
            } else {
                updateBooks(loaded)
            }
        }
    }

    /** 上一页数据到达（对齐 View 版 upDataTop） */
    fun upDataTop(added: List<SearchBook>) {
        topFooter = topFooter.copy(isLoading = false)
        if (added.isEmpty()) return
        updateBooks(added + books)
        // 对齐 View 版 scrollToPositionWithOffset(books.size, 0)：
        // 顶部 footer 占 0 号位时，最后一个新条目位于 added.size
        scrollCommand = ExploreScrollCommand.PrependAnchor(added.size)
        if (oldPage <= 1) {
            topFooter = topFooter.copy(visible = false)
        }
    }

    /** 屏蔽规则变化后全量刷新列表 */
    fun refreshAfterBlock(loaded: List<SearchBook>) {
        footer = footer.copy(isLoading = false)
        updateBooks(loaded)
        if (loaded.isEmpty()) {
            footer = ExploreLoadMoreState(message = appCtx.getString(R.string.empty))
        }
    }

    fun onFooterError(message: String) {
        footer = footer.copy(isLoading = false, hasMore = false, isError = true, message = message)
    }

    fun onTopFooterError(message: String) {
        topFooter = topFooter.copy(isLoading = false, hasMore = false, isError = true, message = message)
    }

    // ── 翻页 ──

    /**
     * 视口变化上报（滚动/数据变化时由列表内容调用）。
     * 停在底部即尝试加载下一页（对齐 View 版 onScrolled 的触发方式，
     * 由 hasMore/loading 状态与冷却间隔防重）；
     * 在顶部继续向上滚则加载上一页。
     */
    fun reportViewport(firstIndex: Int, firstOffset: Int, lastIndex: Int, totalCount: Int) {
        lastFirstVisibleIndex = firstIndex
        lastFirstVisibleOffset = firstOffset
        // 只在有内容时缓存：切换分类清空列表期间的上报不覆盖旧缓存
        if (books.isNotEmpty() && restorePendingUrl == null) {
            currentKindUrl()?.let { url ->
                scrollPositionCache[url] = ExploreScrollSnapshot(firstIndex, firstOffset)
            }
        }
        if (lastIndex >= totalCount - 1) {
            requestLoadNext()
        } else if (firstIndex == 0 && firstOffset == 0 && isScrollingUp(firstIndex, firstOffset)) {
            requestLoadPrev()
        }
        prevViewportFirstIndex = firstIndex
        prevViewportFirstOffset = firstOffset
    }

    private fun isScrollingUp(firstIndex: Int, firstOffset: Int): Boolean {
        if (prevViewportFirstIndex < 0) return false
        return firstIndex < prevViewportFirstIndex ||
            (firstIndex == prevViewportFirstIndex && firstOffset < prevViewportFirstOffset)
    }

    /**
     * 滚动到底部加载下一页。
     * 列数 >3 的网格/瀑布流有 2 秒冷却，冷却期内延迟重试一次（对齐 View 版）。
     */
    fun requestLoadNext(forceLoad: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (layoutMode != EXPLORE_LAYOUT_LIST &&
            effectiveColumnCount() > 3 &&
            now - lastLoadTime < LOAD_COOLDOWN_MS
        ) {
            scheduleLoadRetry(LOAD_COOLDOWN_MS - (now - lastLoadTime))
            return
        }
        if ((footer.hasMore && !footer.isLoading && !topFooter.isLoading) || forceLoad) {
            retryScheduled = false
            lastLoadTime = now
            footer = ExploreLoadMoreState(isLoading = true)
            viewModel.explore()
        }
    }

    private fun scheduleLoadRetry(delayMs: Long) {
        if (retryScheduled) return
        retryScheduled = true
        scope.launch {
            delay(delayMs)
            retryScheduled = false
            requestLoadNext()
        }
    }

    /** 在顶部继续上滑加载上一页 */
    private fun requestLoadPrev(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !footer.isLoading && !topFooter.isLoading) || forceLoad) {
            topFooter = topFooter.copy(
                visible = true, isLoading = true, hasMore = true, isError = false, message = null
            )
            oldPage--
            viewModel.explore(oldPage)
        }
    }

    /** 跳页菜单确认后调用（页码未变化时忽略，对齐 View 版） */
    fun skipPageTo(newPage: Int) {
        if (newPage == currentPage) return
        if (newPage != 1) {
            topFooter = topFooter.copy(visible = true)
        }
        oldPage = newPage
        viewModel.skipPage(newPage)
        updateBooks(emptyList())
        clearAllPending = true
        if (!footer.hasMore) {
            // footer 已到底时不会有滚动事件触发加载，主动强载（对齐 View 版）
            requestLoadNext(forceLoad = true)
        }
    }

    // ── 分类切换 ──

    /**
     * 切换书源前的状态复位（新版发现主界面用）：
     * kinds 由调用方在 initData 之后通过 [onKindsLoaded] 桥接进来，
     * initialExploreUrl 为空时选中项自然落在第一个分类。
     */
    fun resetForNewSource() {
        updateBooks(emptyList())
        footer = ExploreLoadMoreState(isLoading = true)
        topFooter = ExploreLoadMoreState(visible = false)
        oldPage = -1
        currentCategoryIndex = 0
        restorePendingUrl = null
        clearAllPending = false
        scrollPositionCache.clear()
    }

    private fun currentKindUrl(): String? =
        kinds.getOrNull(currentCategoryIndex)?.url?.takeIf { it.isNotBlank() }

    private fun saveCurrentScrollPosition() {
        if (!showCategoryTab || kinds.isEmpty()) return
        currentKindUrl()?.let { url ->
            scrollPositionCache[url] = ExploreScrollSnapshot(lastFirstVisibleIndex, lastFirstVisibleOffset)
        }
    }

    /**
     * 选中分类（Tab 点击 / 手势切换）。位置未变化时忽略，返回选中的分类供更新标题。
     */
    fun selectCategory(position: Int): ExploreKind? {
        if (position !in kinds.indices) return null
        if (position == currentCategoryIndex) return null
        val kind = kinds[position]
        val url = kind.url ?: return null
        saveCurrentScrollPosition()
        currentCategoryIndex = position
        updateBooks(emptyList())
        footer = ExploreLoadMoreState(isLoading = true)
        // VM 已把页码重置为新分类基准页，向上翻页状态同步归零
        oldPage = -1
        topFooter = ExploreLoadMoreState(visible = false)
        restorePendingUrl = url
        viewModel.switchCategory(
            newUrl = url,
            exploreName = kind.title,
            preload = preloadMode == 1,
            allKinds = kinds
        )
        pageTitle = kind.title
        return kind
    }

    /**
     * 新版发现：按分类区选中的 URL 就地加载（不跳转发现列表页）。
     * 与 [selectCategory] 的区别：以 URL 定位（可指向 select/toggle 更新 infoMap
     * 之后的同一个 URL，需要强制重载），无同项短路。
     */
    fun loadExploreUrl(url: String, title: String) {
        currentExploreUrl = url
        viewModel.clearPreloadCache()
        currentCategoryIndex = kinds.indexOfFirst { it.url == url }.coerceAtLeast(0)
        books = emptyList()
        footer = ExploreLoadMoreState(isLoading = true)
        topFooter = ExploreLoadMoreState(visible = false)
        oldPage = -1
        restorePendingUrl = url
        viewModel.switchCategory(
            newUrl = url,
            exploreName = title,
            preload = preloadMode == 1,
            allKinds = kinds
        )
        pageTitle = title
    }

    fun switchToPreviousCategory() {
        selectCategory(currentCategoryIndex - 1)
    }

    fun switchToNextCategory() {
        selectCategory(currentCategoryIndex + 1)
    }

    // ── 菜单操作 ──

    /** 切换布局：列表 → 网格 → 瀑布流 三轮换，切换后恢复首个可见位置 */
    fun switchLayout() {
        val savedIndex = lastFirstVisibleIndex
        layoutMode = (layoutMode + 1) % 3
        actions.persistLayoutMode(layoutMode)
        when (layoutMode) {
            EXPLORE_LAYOUT_GRID -> if (columnGrid < 1 || columnGrid > MAX_COLUMN_COUNT) {
                columnGrid = 2
                actions.persistColumnGrid(2)
            }

            EXPLORE_LAYOUT_WATERFALL -> if (columnWaterfall < 1 || columnWaterfall > MAX_COLUMN_COUNT) {
                columnWaterfall = 2
                actions.persistColumnWaterfall(2)
            }
        }
        scrollCommand = ExploreScrollCommand.RestoreIndex(savedIndex)
    }

    /** 修改当前布局模式的列数并持久化 */
    fun selectColumnCount(count: Int) {
        if (count < 1) return
        val savedIndex = lastFirstVisibleIndex
        if (layoutMode == EXPLORE_LAYOUT_WATERFALL) {
            columnWaterfall = count
            actions.persistColumnWaterfall(count)
        } else {
            columnGrid = count
            actions.persistColumnGrid(count)
        }
        scrollCommand = ExploreScrollCommand.RestoreIndex(savedIndex)
    }

    fun toggleShowCategoryTab() {
        showCategoryTab = !showCategoryTab
        actions.persistShowCategoryTab(showCategoryTab)
    }

    fun togglePreload() {
        preloadMode = if (preloadMode == 0) 1 else 0
        actions.persistPreloadMode(preloadMode)
    }

    /** 屏蔽规则弹窗修改"显示进度指示器"后回写 */
    fun updateShowBlockProgress(show: Boolean) {
        showBlockProgress = show
        actions.persistShowBlockProgress(show)
    }

    fun consumeScrollCommand() {
        scrollCommand = null
        restorePendingUrl = null
    }
}
