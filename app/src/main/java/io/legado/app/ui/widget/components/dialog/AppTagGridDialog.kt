package io.legado.app.ui.widget.components.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.VerticalScrollbar

/**
 * 分类网格选择弹窗：每行 3 个胶囊项、选中态主色高亮、内容可滚动带拖拽滚动块。
 *
 * 用于新版发现/订阅的分类与分组展开选择（替代单列 radio 弹窗）：
 * - 弹窗背景取 surfaceContainerHigh 叠一层透明度，透出底层内容形成半透明观感，
 *   深浅色主题自动跟随（颜色全部来自 MaterialTheme，无硬编码）；
 * - 选中项：主色填充 + onPrimary 文字；未选中项：surface 底 + 描边；
 * - 选项多时限高滚动，右侧 [VerticalScrollbar] 提供拖拽滚动块。
 *
 * @param title 弹窗标题
 * @param options 选项文案，顺序与业务枚举一致
 * @param selectedIndex 当前选中项下标；越界时视作无选中项
 * @param onSelect 选中项回调，参数为下标（选中即回调，由调用点关闭弹窗）
 * @param onDismissRequest 取消或点击弹窗外部时的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTagGridDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            // 半透明：透出底层内容（对齐参考分支 tag picker 的磨砂观感），
            // 取 surfaceContainerHigh 保证深浅色主题下都与背景有对比
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                alpha = AppDimens.TAG_DIALOG_CONTAINER_ALPHA
            ),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = AppDimens.panelRowHorizontalPadding,
                        top = AppDimens.panelRowHorizontalPadding,
                        end = AppDimens.panelRowHorizontalPadding,
                        bottom = AppDimens.panelTitleBottomPadding
                    )
                )
                if (options.isNotEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(max = AppDimens.dialogOptionsMaxHeight)
                    ) {
                        val gridState = rememberLazyGridState()
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(AppDimens.TAG_DIALOG_COLUMNS),
                            state = gridState,
                            // 有滚动块时给右侧留出轨道位置，胶囊不被拖柄压住
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = AppDimens.scrollbarRailWidth / 2),
                            horizontalArrangement =
                                Arrangement.spacedBy(AppDimens.exploreShowTabSpacing),
                            verticalArrangement =
                                Arrangement.spacedBy(AppDimens.exploreShowTabSpacing),
                            contentPadding = PaddingValues(
                                start = AppDimens.panelRowHorizontalPadding,
                                end = AppDimens.panelRowHorizontalPadding,
                                bottom = AppDimens.panelRowTitleSpacing
                            )
                        ) {
                            itemsIndexed(options) { index, label ->
                                TagGridOptionChip(
                                    label = label,
                                    selected = index == selectedIndex,
                                    onClick = { onSelect(index) }
                                )
                            }
                        }
                        VerticalScrollbar(
                            state = gridState,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            end = AppDimens.panelRowTitleSpacing,
                            bottom = AppDimens.panelRowTitleSpacing
                        )
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

/** 网格里的单个分类胶囊：选中主色填充，未选中 surface 底描边 */
@Composable
private fun TagGridOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.exploreShowTabCornerRadius),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(
                AppDimens.exploreShowTabBorderWidth,
                MaterialTheme.colorScheme.outlineVariant
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = AppDimens.exploreShowTabHorizontalPadding,
                vertical = AppDimens.exploreShowTabVerticalPadding * 1.5f
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
