package io.legado.app.ui.main.rss.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.removeSortCache
import io.legado.app.help.source.sortUrls
import io.legado.app.model.blockrule.BlockRuleStore
import io.legado.app.ui.rss.article.RssArticlesViewModel
import io.legado.app.ui.rss.article.RssSortViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** 新版订阅文章列表的加载 footer 状态（语义对齐 View 版 LoadMoreView） */
data class RssLoadMoreState(
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val message: String? = null,
    val isError: Boolean = false,
)

/**
 * 新版订阅状态持有者：源选择行 + 分类标签栏 + 内嵌文章列表。
 *
 * 文章数据与 View 版 RssSortActivity 一致：抓取结果由 [RssArticlesViewModel] 写库，
 * UI 收集 rssArticleDao 的 flow 并做屏蔽规则过滤；分页靠 ruleNextPage 的
 * nextUrl 递增页码。
 *
 * @param articlesViewModel 文章加载（复用分类页 ViewModel）
 * @param sortViewModel 源级操作（清除文章缓存 / 清除分类缓存）
 * @param scope 生命周期作用域（viewLifecycleOwner）
 */
class ModernRssController(
    private val articlesViewModel: RssArticlesViewModel,
    private val sortViewModel: RssSortViewModel,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var source by mutableStateOf<RssSource?>(null)
        private set
    var sorts by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var selectedSortIndex by mutableIntStateOf(0)
        private set
    var articles by mutableStateOf<List<RssArticle>>(emptyList())
        private set
    var blockedCount by mutableIntStateOf(0)
        private set

    /** 文章布局：false=列表（对齐 articleStyle 0），true=双列网格（对齐 articleStyle 2） */
    var gridMode by mutableStateOf(false)
        private set
    var footer by mutableStateOf(RssLoadMoreState(isLoading = true))
        private set

    /** 当前页码（顶栏跳页菜单显示用） */
    var currentPage by mutableIntStateOf(1)
        private set

    private var rawArticles: List<RssArticle> = emptyList()
    private var dbFlowJob: Job? = null

    /** 屏蔽规则弹窗需要的原始未过滤文章列表（对齐 View 版 rawArticles） */
    fun rawArticlesForRules(): List<RssArticle> = rawArticles

    /** 切换订阅源：解析分类后加载第一个分类 */
    fun selectSource(source: RssSource) {
        this.source = source
        gridMode = source.articleStyle == 2
        footer = RssLoadMoreState(isLoading = true)
        scope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) { source.sortUrls() }
            }.getOrElse {
                AppLog.put("新版订阅获取分类失败", it)
                emptyList()
            }.ifEmpty { listOf(Pair("", source.sourceUrl)) }
            sorts = loaded
            loadSort(0)
        }
    }

    /** 切换分类 */
    fun selectSort(index: Int) {
        if (index !in sorts.indices || index == selectedSortIndex) return
        selectedSortIndex = index
        loadSort(index)
    }

    private fun loadSort(index: Int) {
        val source = source ?: return
        selectedSortIndex = index
        val sort = sorts.getOrNull(index) ?: return
        articles = emptyList()
        rawArticles = emptyList()
        blockedCount = 0
        footer = RssLoadMoreState(isLoading = true)
        observeDbFlow(source.sourceUrl, sort.first)
        articlesViewModel.init(sort.first, sort.second)
        articlesViewModel.loadArticles(source)
    }

    /** 收集当前分类的文章库流并应用屏蔽规则（对齐 View 版 initData） */
    private fun observeDbFlow(origin: String, sortName: String) {
        dbFlowJob?.cancel()
        dbFlowJob = scope.launch {
            appDb.rssArticleDao.flowByOriginSort(origin, sortName)
                .flowOn(Dispatchers.IO)
                .collect { newList ->
                    rawArticles = newList
                    applyBlockRulesTo(newList)
                }
        }
    }

    private fun applyBlockRulesTo(loaded: List<RssArticle>) {
        val sourceUrl = source?.sourceUrl.orEmpty()
        val filtered = runCatching {
            BlockRuleStore.filterRssArticles(appCtx, loaded, sourceUrl)
        }.getOrDefault(loaded)
        blockedCount = loaded.size - filtered.size
        articles = filtered
    }

    /** 屏蔽规则变化后重新过滤当前文章列表 */
    fun applyBlockRules() {
        BlockRuleStore.invalidateCache()
        applyBlockRulesTo(rawArticles)
    }

    /** 滚动到底加载下一页（由视口触发，语义对齐 View 版 onScrolled） */
    fun requestLoadMore(forceLoad: Boolean = false) {
        val source = source ?: return
        if (footer.isLoading) return
        if ((footer.hasMore && articles.isNotEmpty()) || forceLoad) {
            footer = RssLoadMoreState(isLoading = true)
            articlesViewModel.loadMore(source)
        }
    }

    /** 跳页菜单确认后调用 */
    fun skipPageTo(page: Int) {
        val source = source ?: return
        if (page == currentPage) return
        footer = RssLoadMoreState(isLoading = true)
        articlesViewModel.skipPage(page)
        articlesViewModel.loadArticles(source, page)
    }

    /** 文章布局切换：列表 ↔ 双列网格，按源持久化 */
    fun toggleLayout() {
        val source = source ?: return
        gridMode = !gridMode
        source.articleStyle = if (gridMode) 2 else 0
        scope.launch(Dispatchers.IO) {
            appDb.rssSourceDao.update(source)
        }
    }

    /** 刷新分类：清除分类缓存后重新解析 */
    fun refreshSorts() {
        val source = source ?: return
        scope.launch(Dispatchers.IO) { source.removeSortCache() }
        footer = RssLoadMoreState(isLoading = true)
        selectSource(source)
    }

    /** 清除当前源的文章缓存（对齐 View 版 menu_clear） */
    fun clearArticles() {
        sortViewModel.clearArticles()
    }

    // ── Fragment LiveData 桥接 ──

    fun onPageChanged(page: Int) {
        currentPage = page
    }

    fun onFinally(hasMore: Boolean) {
        footer = footer.copy(isLoading = false, hasMore = hasMore)
    }

    fun onError(message: String) {
        footer = footer.copy(isLoading = false, hasMore = false, isError = true, message = message)
    }
}
