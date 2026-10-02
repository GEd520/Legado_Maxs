package io.legado.app.ui.main.explore.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.book.explore.compose.ExploreShowActions
import io.legado.app.ui.book.explore.compose.ExploreShowController
import io.legado.app.ui.book.explore.compose.ExploreShowListContent
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.dialog.AppRadioChoiceDialog
import io.legado.app.ui.widget.components.BlockProgressChip
import io.legado.app.ui.widget.components.CategoryTabs
import io.legado.app.ui.widget.components.BookBottomSheet
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState

/**
 * 新版发现：在发现主 Tab 内直接显示"源选择行 + 分类标签栏 + 内容列表"，
 * 省掉"书源列表 → 分类列表"的两级跳转（参考 Legado_R 新版发现）。
 *
 * 内容区复用 [ExploreShowListContent]（三布局 + 双向翻页 + 手势切分类），
 * 菜单动作由宿主 Fragment 的 TitleBar 三点菜单承担，经 [actions] 下发。
 *
 * @param controller 页面状态（与独立发现列表页同一套控制器）
 * @param sources 启用发现的书源列表（Flow 实时数据）
 * @param selectedSourceUrl 当前选中的源
 * @param onSelectSource 选中源回调（宿主负责持久化与触发重新加载）
 * @param showBlockProgress 是否显示屏蔽进度芯片
 */
@Composable
fun ModernExploreContent(
    controller: ExploreShowController,
    actions: ExploreShowActions,
    sources: List<BookSourcePart>,
    selectedSourceUrl: String?,
    onSelectSource: (BookSourcePart) -> Unit,
    showBlockProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showBookSheet by remember { mutableStateOf(false) }
    var sheetBook by remember { mutableStateOf<SearchBook?>(null) }
    var sheetShelfState by remember { mutableStateOf(BookShelfState.NOT_IN_SHELF) }

    Column(modifier.fillMaxSize()) {
        ModernExploreSourceRow(
            sources = sources,
            selectedSourceUrl = selectedSourceUrl,
            onPickSource = { showSourcePicker = true },
            modifier = Modifier.fillMaxWidth()
        )
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
            if (showBlockProgress && controller.blockedCount > 0) {
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

    if (showSourcePicker) {
        AppRadioChoiceDialog(
            title = stringResource(R.string.discovery),
            options = sources.map { it.bookSourceName },
            selectedIndex = sources.indexOfFirst { it.bookSourceUrl == selectedSourceUrl },
            onSelect = { index ->
                showSourcePicker = false
                sources.getOrNull(index)?.let(onSelectSource)
            },
            onDismissRequest = { showSourcePicker = false }
        )
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

/** 源选择行：当前源名的胶囊按钮，点击弹出源选择弹窗 */
@Composable
private fun ModernExploreSourceRow(
    sources: List<BookSourcePart>,
    selectedSourceUrl: String?,
    onPickSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentName = sources
        .firstOrNull { it.bookSourceUrl == selectedSourceUrl }
        ?.bookSourceName
        ?: sources.firstOrNull()?.bookSourceName
        ?: stringResource(R.string.discovery)

    Row(
        modifier.padding(
            horizontal = AppDimens.exploreShowTabsHorizontalPadding,
            vertical = AppDimens.exploreShowTabsVerticalPadding
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            onClick = onPickSource
        ) {
            Text(
                text = currentName,
                modifier = Modifier.padding(
                    horizontal = AppDimens.exploreShowTabHorizontalPadding,
                    vertical = AppDimens.exploreShowTabVerticalPadding
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
