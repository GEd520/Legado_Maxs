package io.legado.app.ui.main.rss

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.FragmentRssBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.rss.compose.RssSourceActions
import io.legado.app.ui.main.rss.compose.RssSourceGrid
import io.legado.app.ui.main.rss.compose.RssSourceItem
import io.legado.app.ui.main.rss.compose.RssSourceMenuAction
import io.legado.app.ui.main.rss.compose.toRssSourceItems
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.source.manage.RssSourceSort
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 订阅界面。
 *
 * 结构分工与书架 / 发现 / 我的页一致：顶栏（搜索框、分组菜单）仍是 View 体系——
 * 主界面是 ViewPager + TitleBar 的 View 宿主，顶栏颜色必须继续走 TopBarConfig 统一体系；
 * 顶栏以下的订阅源网格整体 Compose 化（内容见 [RssSourceGrid]）。
 *
 * 本类是宿主：持有搜索词、排序与底栏内边距，执行平台操作（开 Activity、弹对话框），
 * 把搜索 / 分组过滤并排序后的订阅源列表交给 Compose 渲染。
 */
class RssFragment() : VMBaseFragment<RssViewModel>(R.layout.fragment_rss), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentRssBinding::bind)
    override val viewModel by viewModels<RssViewModel>()
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    /** Compose 侧直接读这些快照状态，写入即触发重组 */
    private var sourceItems by mutableStateOf<List<RssSource>>(emptyList())

    /** 条目 UI 模型：过滤排序后的结果转一次，网格重组时不再逐项加工 */
    private var displayItems by mutableStateOf<List<RssSourceItem>>(emptyList())
    private var bottomPaddingPx by mutableIntStateOf(0)

    private var groupsFlowJob: Job? = null
    private var rssFlowJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var groupsMenu: SubMenu? = null

    private val actions by lazy {
        RssSourceActions(
            onOpen = ::openRss,
            onMenuAction = ::onSourceMenuAction,
            onOpenRuleSub = { startActivity<RuleSubActivity>() },
        )
    }

    /**
     * 订阅源排序方式，从 SharedPreferences 读取，与订阅源管理页面同步
     */
    private val sort: RssSourceSort
        get() = RssSourceSort.entries[appCtx.getPrefInt(PreferKey.rssSourceSort, 0)]

    /**
     * 排序方向，从 SharedPreferences 读取
     */
    private val sortAscending: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.rssSourceSortAscending, true)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        // 首次进入时主动取一次底栏高度，之后由 MainActivity 通过接口推送变化
        bottomPaddingPx = (activity as? MainActivity)?.mainContentBottomPadding() ?: 0
        initSearchView()
        initComposeContent()
        initGroupData()
        upRssFlowJob()
    }

    private fun initComposeContent() {
        binding.composeSourceGrid.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeSourceGrid.setContent {
            LegadoTheme {
                RssSourceGrid(
                    sourceItems = displayItems,
                    bottomPaddingPx = bottomPaddingPx,
                    actions = actions,
                )
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_rss, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_read_record -> showDialogFragment<ReadRecordDialog>()
            R.id.menu_rss_config -> startActivity<RssSourceActivity>()
            R.id.menu_rss_star -> startActivity<RssFavoritesActivity>()
            else -> if (item.groupId == R.id.menu_group_text) {
                searchView.setQuery("group:${item.title}", true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.rss)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upRssFlowJob(newText)
                return false
            }
        })
    }

    override fun updateMainBottomPadding(bottomPadding: Int) {
        bottomPaddingPx = bottomPadding
    }

    private fun initGroupData() {
        groupsFlowJob?.cancel()
        groupsFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssSourceDao.flowEnabledGroups().catch {
                AppLog.put("订阅界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupsMenu()
            }
        }
    }

    private fun upRssFlowJob(searchKey: String? = null) {
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.rssSourceDao.flowEnabledByGroup(key)
                }

                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.map { data ->
                // 应用排序逻辑，与订阅源管理页面保持一致
                when (sort) {
                    RssSourceSort.Name -> {
                        if (sortAscending) data.sortedWith { o1, o2 ->
                            o1.sourceName.cnCompare(o2.sourceName)
                        } else data.sortedWith { o1, o2 ->
                            o2.sourceName.cnCompare(o1.sourceName)
                        }
                    }
                    RssSourceSort.Url -> {
                        if (sortAscending) data.sortedBy { it.sourceUrl }
                        else data.sortedByDescending { it.sourceUrl }
                    }
                    RssSourceSort.Update -> {
                        if (sortAscending) data.sortedBy { it.lastUpdateTime }
                        else data.sortedByDescending { it.lastUpdateTime }
                    }
                    RssSourceSort.Enable -> {
                        if (sortAscending) data.sortedWith { o1, o2 ->
                            var sortNum = -o1.enabled.compareTo(o2.enabled)
                            if (sortNum == 0) sortNum = o1.sourceName.cnCompare(o2.sourceName)
                            sortNum
                        } else data.sortedWith { o1, o2 ->
                            var sortNum = o1.enabled.compareTo(o2.enabled)
                            if (sortNum == 0) sortNum = o1.sourceName.cnCompare(o2.sourceName)
                            sortNum
                        }
                    }
                    else -> data // 手动排序时，数据库已按 customOrder 排序
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                sourceItems = it
                displayItems = it.toRssSourceItems()
            }
        }
    }

    // ── 订阅源条目动作（由 Compose 侧回调） ──

    private fun onSourceMenuAction(item: RssSourceItem, action: RssSourceMenuAction) {
        // 条目模型只带 url，置顶 / 删除 / 禁用要拿完整的 RssSource，从当前列表里反查
        val source = sourceItems.firstOrNull { it.sourceUrl == item.sourceUrl } ?: return
        when (action) {
            RssSourceMenuAction.Edit -> edit(source)
            RssSourceMenuAction.ToTop -> viewModel.topSource(source)
            RssSourceMenuAction.Login -> login(source)
            RssSourceMenuAction.Disable -> viewModel.disable(source)
            RssSourceMenuAction.Delete -> del(source)
        }
    }

    private fun openRss(item: RssSourceItem) {
        val rssSource = sourceItems.firstOrNull { it.sourceUrl == item.sourceUrl } ?: return
        if (rssSource.singleUrl) {
            viewModel.getSingleUrl(rssSource) { url ->
                if (url.startsWith("http", true)) {
                    ReadRssActivity.start(
                        requireContext(),
                        true,
                        rssSource.sourceUrl,
                        rssSource.sourceName,
                        url
                    )
                } else {
                    context?.openUrl(url)
                }
            }
        } else {
            viewModel.launchRssWithHtml(rssSource, {
                startActivity<RssSortActivity> {
                    putExtra("sourceUrl", rssSource.sourceUrl)
                }
            }) { html ->
                ReadRssActivity.start(
                    requireContext(),
                    true,
                    rssSource.sourceUrl,
                    rssSource.sourceName,
                    startHtml = html
                )
            }
        }
    }

    private fun login(rssSource: RssSource) {
        startActivity<SourceLoginActivity> {
            putExtra("type", "rssSource")
            putExtra("key", rssSource.sourceUrl)
        }
    }

    private fun edit(rssSource: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", rssSource.sourceUrl)
        }
    }

    private fun del(rssSource: RssSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rssSource.sourceName)
            noButton()
            yesButton {
                viewModel.del(rssSource)
            }
        }
    }
}
