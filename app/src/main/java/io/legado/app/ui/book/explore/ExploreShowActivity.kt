package io.legado.app.ui.book.explore

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.explore.compose.ExploreShowActions
import io.legado.app.ui.book.explore.compose.ExploreShowController
import io.legado.app.ui.book.explore.compose.ExploreShowInitArgs
import io.legado.app.ui.book.explore.compose.ExploreShowScreen
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.blockrule.BlockRuleConfigDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

/**
 * 发现列表（发现 → 点进某个书源分类后的界面），Compose 实现。
 *
 * Activity 只保留三类职责：
 * 1. 平台操作（弹窗、跳转、toast、持久化），通过 [ExploreShowActions] 提供给 UI；
 * 2. ViewModel LiveData → [ExploreShowController] 的状态桥接；
 * 3. [GroupSelectDialog] 回调。
 */
class ExploreShowActivity : BaseComposeActivity(), GroupSelectDialog.CallBack {

    companion object {
        private const val REQUEST_CODE_ADD_ALL_TO_SHELF = 1001
    }

    private val viewModel by viewModels<ExploreShowViewModel>()

    /** 当前书源 URL，用于按书源隔离布局配置 */
    private val sourceUrl: String by lazy { intent.getStringExtra("sourceUrl") ?: "" }

    private val controller: ExploreShowController by lazy {
        ExploreShowController(
            viewModel = viewModel,
            actions = actions,
            initialArgs = buildInitArgs(),
            scope = viewModel.viewModelScope
        )
    }

    private val actions: ExploreShowActions by lazy { buildActions() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.booksData.observe(this) { controller.upData(it) }
        viewModel.addBooksData.observe(this) { controller.upDataTop(it) }
        viewModel.blockRulesRefreshData.observe(this) { controller.refreshAfterBlock(it) }
        viewModel.blockedCountData.observe(this) { controller.onBlockedCountChanged(it) }
        viewModel.exploreKindsData.observe(this) { controller.onKindsLoaded(it) }
        viewModel.errorLiveData.observe(this) { controller.onFooterError(it) }
        viewModel.errorTopLiveData.observe(this) { controller.onTopFooterError(it) }
        viewModel.upAdapterLiveData.observe(this) { controller.onShelfStateChanged() }
        viewModel.pageLiveData.observe(this) { controller.onPageChanged(it) }
        viewModel.addAllToShelfResult.observe(this) { count ->
            if (count == 0) {
                toastOnUi(R.string.all_books_in_shelf)
            } else {
                toastOnUi(getString(R.string.add_books_success, count))
            }
        }
        viewModel.initData(intent)
    }

    private fun buildInitArgs(): ExploreShowInitArgs = ExploreShowInitArgs(
        exploreName = intent.getStringExtra("exploreName") ?: getString(R.string.discovery),
        exploreUrl = intent.getStringExtra("exploreUrl") ?: "",
        layoutMode = getPrefInt("${PreferKey.exploreGridMode}_$sourceUrl", 0),
        columnGrid = getPrefInt("${PreferKey.exploreShowColumn}_$sourceUrl", 2),
        columnWaterfall = getPrefInt("${PreferKey.exploreShowColumnWaterfall}_$sourceUrl", 2),
        showCategoryTab = getPrefBoolean("${PreferKey.exploreShowCategoryTab}_$sourceUrl", false),
        preloadMode = getPrefInt("${PreferKey.exploreShowPreload}_$sourceUrl", 0),
        showBlockProgress = getPrefBoolean(PreferKey.blockRuleShowProgress, false)
    )

    private fun buildActions() = ExploreShowActions(
        onBackClick = { finish() },
        onPagePick = { current, onPicked ->
            NumberPickerDialog(this)
                .setTitle(getString(R.string.change_page))
                .setMaxValue(999)
                .setMinValue(1)
                .setValue(current)
                .show(onPicked)
        },
        onColumnPick = { current, onPicked ->
            NumberPickerDialog(this)
                .setTitle(getString(R.string.select_column_count))
                .setMaxValue(10)
                .setMinValue(1)
                .setValue(current)
                .show(onPicked)
        },
        onAddAllToShelfClick = {
            showDialogFragment(GroupSelectDialog(0, REQUEST_CODE_ADD_ALL_TO_SHELF))
        },
        onShowBlockRuleClick = { showBlockRuleConfig() },
        onShowBookInfo = { book -> showBookInfo(book) },
        addToShelf = { viewModel.addToShelf(it) },
        persistLayoutMode = { putPrefInt("${PreferKey.exploreGridMode}_$sourceUrl", it) },
        persistColumnGrid = { putPrefInt("${PreferKey.exploreShowColumn}_$sourceUrl", it) },
        persistColumnWaterfall = {
            putPrefInt("${PreferKey.exploreShowColumnWaterfall}_$sourceUrl", it)
        },
        persistShowCategoryTab = {
            putPrefBoolean("${PreferKey.exploreShowCategoryTab}_$sourceUrl", it)
        },
        persistPreloadMode = { putPrefInt("${PreferKey.exploreShowPreload}_$sourceUrl", it) },
        persistShowBlockProgress = { putPrefBoolean(PreferKey.blockRuleShowProgress, it) }
    )

    private fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
            putExtra("origin", book.origin)
        }
    }

    /** 打开屏蔽规则配置弹窗 */
    private fun showBlockRuleConfig() {
        val dialog = BlockRuleConfigDialog()
        dialog.sourceUrl = sourceUrl
        dialog.allBooks = viewModel.allBooksList
        dialog.onRulesChanged = {
            viewModel.applyBlockRules(sourceUrl)
        }
        dialog.onShowProgressChanged = {
            controller.updateShowBlockProgress(it)
        }
        dialog.show(supportFragmentManager, "exploreBlockRuleConfig")
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        if (requestCode == REQUEST_CODE_ADD_ALL_TO_SHELF) {
            toastOnUi(getString(R.string.adding_books, viewModel.booksCount))
            viewModel.addAllToShelf(groupId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.clearCaches()
    }

    @Composable
    override fun ComposeContent() {
        ExploreShowScreen(controller = controller, actions = actions)
    }
}
