package io.legado.app.ui.rss.source.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssSourceDebugBinding
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.invisible
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.visible
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import splitties.views.onClick
import splitties.views.onLongClick


class RssSourceDebugActivity : VMBaseActivity<ActivityRssSourceDebugBinding, RssSourceDebugModel>() {

    override val binding by viewBinding(ActivityRssSourceDebugBinding::inflate)
    override val viewModel by viewModels<RssSourceDebugModel>()

    private val adapter by lazy { RssSourceDebugAdapter(this) }
    private val searchView: androidx.appcompat.widget.SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var findMatches: List<Int> = emptyList()
    private var findIndex = -1
    private lateinit var findBackCallback: OnBackPressedCallback

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initSearchView()
        initFindBar()
        viewModel.initData(intent.getStringExtra("key")) {
            initHelpView()
        }
        viewModel.observe { state, msg ->
            lifecycleScope.launch {
                adapter.addItem(msg)
                refreshFindCount()
                if (state == -1 || state == 1000) {
                    binding.rotateLoading.gone()
                    binding.fbStop.invisible()
                }
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_source_debug, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_find_text -> toggleFindBar()
            R.id.menu_list_src -> showDialogFragment(TextDialog("Html", viewModel.listSrc))
            R.id.menu_content_src -> showDialogFragment(TextDialog("Html", viewModel.contentSrc))
            R.id.menu_preview_source_url -> showPreview(R.string.preview_title_source_url, null)
            R.id.menu_preview_start_page -> showPreview(
                R.string.preview_title_start_page,
                viewModel.listSrc
            )
            R.id.menu_preview_description -> showPreview(
                R.string.preview_title_description,
                viewModel.listSrc
            )
            R.id.menu_preview_content -> showPreview(
                R.string.preview_title_content,
                viewModel.contentSrc
            )
        }
        return super.onCompatOptionsItemSelected(item)
    }

    // 显示预览(url、启动页、描述规则、内容页)
    // @param titleResId 标题资源ID
    // @param html 内容HTML
    private fun showPreview(titleResId: Int, html: String?) {
        val sourceUrl = viewModel.rssSource?.sourceUrl ?: return
        showDialogFragment(
            BottomWebViewDialog(
                sourceKey = sourceUrl,
                bookType = 0,
                url = sourceUrl,
                html = html,
                title = getString(R.string.preview_title_format, getString(titleResId))
            )
        )
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.rotateLoading.loadingColor = accentColor
        binding.fbStop.backgroundTintList = Selector.colorBuild()
            .setDefaultColor(accentColor)
            .setPressedColor(ColorUtils.darkenColor(accentColor))
            .create()
        binding.fbStop.setOnClickListener {
            stopDebug()
        }
        binding.fbStop.applyNavigationBarMargin(true)
    }

    private fun initSearchView() {
        openOrCloseHelp(true)
        searchView.onActionViewExpanded()
        searchView.isSubmitButtonEnabled = true
        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                openOrCloseHelp(false)
                startSearch(query ?: "我的")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            openOrCloseHelp(hasFocus)
        }
    }
    @SuppressLint("SetTextI18n")
    private fun initHelpView() {
        binding.textMy.onClick {
            searchView.setQuery(binding.textMy.text, true)
        }
        binding.textXt.onClick {
            searchView.setQuery(binding.textXt.text, true)
        }
        binding.textFl.onClick {
            if (!binding.textFl.text.startsWith("ERROR:")) {
                searchView.setQuery(binding.textFl.text, true)
            }
        }
        binding.textContent.onClick {
            if (!searchView.query.isNullOrBlank()) {
                searchView.setQuery(searchView.query, true)
            }
        }
        initSortKinds()
    }

    private fun initSortKinds() {
        lifecycleScope.launch {
            val sortKinds = viewModel.rssSource?.sortUrls()?.filter {
                it.second.isNotBlank()
            }
            sortKinds?.firstOrNull()?.let {
                binding.textFl.text = "${it.first}::${it.second}"
                if (it.first.startsWith("ERROR:")) {
                    adapter.addItem("获取发现出错\n${it.second}")
                    openOrCloseHelp(false)
                    searchView.clearFocus()
                    return@launch
                }
            }
            @Suppress("USELESS_ELVIS")
            sortKinds?.map { it.first ?: "" }?.let { sortKindTitles ->
                binding.textFl.onLongClick {
                    selector("选择分类", sortKindTitles) { _, index ->
                        val sort = sortKinds[index]
                        binding.textFl.text = "${sort.first}::${sort.second}"
                        searchView.setQuery(binding.textFl.text, true)
                    }
                }
            }
        }
    }

    /**
     * 打开关闭辅助面板
     */
    private fun openOrCloseHelp(open: Boolean) {
        if (open) {
            binding.help.visibility = View.VISIBLE
        } else {
            binding.help.visibility = View.GONE
        }
    }
    private fun startSearch(key: String) {
        adapter.clearItems()
        viewModel.startDebug(key, {
            binding.rotateLoading.visible()
            binding.fbStop.visible()
        }, {
            binding.rotateLoading.gone()
            binding.fbStop.invisible()
            toastOnUi("未获取到书源")
        })
    }

    /**
     * 手动停止调试：取消调试任务并收尾界面状态
     */
    private fun stopDebug() {
        viewModel.stopDebug()
        binding.rotateLoading.gone()
        binding.fbStop.invisible()
        adapter.addItem("■ 已手动停止调试")
    }

    /**
     * 初始化日志文本查找栏（默认关闭，由菜单开关控制）
     */
    private fun initFindBar() {
        binding.findBar.onSearch = { query ->
            performFind(query)
        }
        binding.findBar.onNext = { moveFind(1) }
        binding.findBar.onPrev = { moveFind(-1) }
        binding.findBar.onClose = { closeFindBar() }
        findBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeFindBar()
            }
        }
        onBackPressedDispatcher.addCallback(this, findBackCallback)
    }

    private fun toggleFindBar() {
        if (binding.findBar.isVisible) {
            closeFindBar()
        } else {
            binding.findBar.show()
            findBackCallback.isEnabled = true
        }
    }

    private fun closeFindBar() {
        findMatches = emptyList()
        findIndex = -1
        binding.findBar.hide()
        binding.findBar.clearCount()
        findBackCallback.isEnabled = false
        adapter.setSearch(null)
    }

    private fun performFind(query: String) {
        if (query.isBlank()) {
            findMatches = emptyList()
            findIndex = -1
            binding.findBar.clearCount()
            adapter.setSearch(null)
            return
        }
        val currentLine = findMatches.getOrNull(findIndex) ?: -1
        findMatches = findMatchLines(query)
        findIndex = when {
            findMatches.isEmpty() -> -1
            currentLine in findMatches -> findMatches.indexOf(currentLine)
            else -> 0
        }
        adapter.setSearch(query, findMatches.getOrNull(findIndex) ?: -1)
        scrollToCurrentFind()
        binding.findBar.setCount(findIndex + 1, findMatches.size)
    }

    private fun moveFind(step: Int) {
        if (findMatches.isEmpty()) {
            return
        }
        findIndex = (findIndex + step + findMatches.size) % findMatches.size
        adapter.setCurrentLine(findMatches[findIndex])
        scrollToCurrentFind()
        binding.findBar.setCount(findIndex + 1, findMatches.size)
    }

    /**
     * 调试中新日志到达时重算匹配；保持当前定位行不变，不自动滚动
     */
    private fun refreshFindCount() {
        if (!binding.findBar.isVisible) {
            return
        }
        val query = binding.findBar.query
        if (query.isBlank()) {
            return
        }
        val currentLine = findMatches.getOrNull(findIndex) ?: -1
        findMatches = findMatchLines(query)
        findIndex = findMatches.indexOf(currentLine).takeIf { it >= 0 }
            ?: if (findMatches.isEmpty()) -1 else findMatches.lastIndex
        val newLine = findMatches.getOrNull(findIndex) ?: -1
        if (newLine != currentLine) {
            adapter.setCurrentLine(newLine)
        }
        binding.findBar.setCount(findIndex + 1, findMatches.size)
    }

    private fun findMatchLines(query: String): List<Int> =
        adapter.getItems().withIndex()
            .filter { it.value.contains(query, ignoreCase = true) }
            .map { it.index }

    private fun scrollToCurrentFind() {
        val line = findMatches.getOrNull(findIndex) ?: return
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        layoutManager.scrollToPositionWithOffset(line, binding.recyclerView.height / 4)
    }
}