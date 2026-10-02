package io.legado.app.ui.book.explore.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.AppPageTopBar
import io.legado.app.ui.widget.components.BlockProgressChip
import io.legado.app.ui.widget.components.CategoryTabs
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.BookBottomSheet

/**
 * 发现列表页（发现 → 点进某个书源分类后的界面）。
 *
 * 结构：顶栏（返回 / 选列图标 / 跳页 / 溢出菜单）+ 多行分类 Tab +
 * 三种布局模式的内容区，列表上方悬浮屏蔽规则进度芯片，
 * 长按条目弹出书籍底部弹窗。
 *
 * @param controller 页面状态持有者（LiveData 桥接由 Activity 完成）
 * @param actions 平台操作回调（弹窗/导航/持久化，实现在 Activity）
 */
@Composable
fun ExploreShowScreen(
    controller: ExploreShowController,
    actions: ExploreShowActions,
    modifier: Modifier = Modifier,
) {
    var showBookSheet by remember { mutableStateOf(false) }
    var sheetBook by remember { mutableStateOf<SearchBook?>(null) }
    var sheetShelfState by remember { mutableStateOf(BookShelfState.NOT_IN_SHELF) }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ExploreShowTopBar(controller = controller, actions = actions)
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (controller.showCategoryTab && controller.kinds.isNotEmpty()) {
                CategoryTabs(
                    titles = controller.kinds.map { it.title },
                    selectedIndex = controller.currentCategoryIndex,
                    onSelect = { controller.selectCategory(it) }
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                ExploreShowListContent(
                    controller = controller,
                    onShowBookInfo = actions.onShowBookInfo,
                    onBookLongClick = { book ->
                        sheetBook = book
                        sheetShelfState = controller.getBookShelfState(book)
                        showBookSheet = true
                    }
                )
                if (controller.showBlockProgress && controller.blockedCount > 0) {
                    BlockProgressChip(
                        blockedCount = controller.blockedCount,
                        onClick = actions.onShowBlockRuleClick,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                start = AppDimens.exploreShowBlockChipOuterHorizontal,
                                top = AppDimens.exploreShowBlockChipOuterTop,
                                end = AppDimens.exploreShowBlockChipOuterHorizontal
                            )
                    )
                }
            }
        }
    }

    if (showBookSheet) {
        BookBottomSheet(
            show = true,
            book = sheetBook,
            shelfState = sheetShelfState,
            onDismiss = { showBookSheet = false },
            onAddToShelf = { actions.addToShelf(it) },
            onShowInfo = { actions.onShowBookInfo(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreShowTopBar(
    controller: ExploreShowController,
    actions: ExploreShowActions,
    modifier: Modifier = Modifier,
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val layoutModeName = stringResource(
        when (controller.layoutMode) {
            EXPLORE_LAYOUT_GRID -> R.string.switch_layout_grid
            EXPLORE_LAYOUT_WATERFALL -> R.string.switch_layout_waterfall
            else -> R.string.switch_layout_list
        }
    )

    AppPageTopBar(
        title = controller.pageTitle,
        onBackClick = actions.onBackClick,
        modifier = modifier,
        actions = {
            if (controller.layoutMode != EXPLORE_LAYOUT_LIST) {
                IconButton(
                    onClick = {
                        actions.onColumnPick(controller.effectiveColumnCount()) {
                            controller.selectColumnCount(it)
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_view_quilt),
                        contentDescription = stringResource(R.string.select_column_count)
                    )
                }
            }
            TextButton(
                onClick = {
                    actions.onPagePick(controller.currentPage) { controller.skipPageTo(it) }
                }
            ) {
                Text(
                    text = stringResource(R.string.menu_page, controller.currentPage),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more)
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_all_to_shelf)) },
                        onClick = {
                            showOverflowMenu = false
                            actions.onAddAllToShelfClick()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.switch_layout_current, layoutModeName
                                )
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            controller.switchLayout()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.explore_block_rule)) },
                        onClick = {
                            showOverflowMenu = false
                            actions.onShowBlockRuleClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_category_tab)) },
                        trailingIcon = {
                            Checkbox(
                                checked = controller.showCategoryTab,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            controller.toggleShowCategoryTab()
                        }
                    )
                    if (controller.showCategoryTab) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.explore_preload)) },
                            trailingIcon = {
                                Checkbox(
                                    checked = controller.preloadMode == 1,
                                    onCheckedChange = null
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                controller.togglePreload()
                            }
                        )
                    }
                }
            }
        }
    )
}
