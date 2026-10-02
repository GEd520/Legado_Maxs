package io.legado.app.ui.widget.components.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.BasicAlertDialog
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composeActionRadius
import io.legado.app.ui.widget.components.VerticalScrollbar

/**
 * 可搜索的单选列表弹窗：顶部搜索框实时过滤选项，列表限高滚动带拖拽滚动块。
 *
 * 用于新版发现/订阅左上角的源切换弹窗（源列表可达数百项，必须可搜索）：
 * - 过滤规则：忽略大小写的包含匹配（拼音/首字母不参与，保持与源搜索页一致的最简口径）；
 * - 弹窗背景/文字全部取自 MaterialTheme，深浅色主题自动跟随；
 * - 选项多时限高滚动，右侧 [VerticalScrollbar] 提供拖拽滚动块。
 *
 * @param title 弹窗标题
 * @param options 选项文案，顺序与业务枚举一致
 * @param selectedIndex 当前选中项下标（基于 [options] 原始下标）；越界时视作无选中项
 * @param onSelect 选中项回调，参数为 [options] 原始下标（选中即回调，由调用点关闭弹窗）
 * @param onDismissRequest 取消或点击弹窗外部时的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchableChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    // 搜索词变化时按包含关系过滤，保留原始下标供回调
    val filtered = remember(options, query) {
        if (query.isBlank()) {
            options.indices.toList()
        } else {
            val q = query.trim()
            options.indices.filter { options[it].contains(q, ignoreCase = true) }
        }
    }

    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = AppDimens.panelRowHorizontalPadding,
                        top = AppDimens.panelRowHorizontalPadding,
                        end = AppDimens.panelRowHorizontalPadding,
                        bottom = AppDimens.panelTitleBottomPadding
                    )
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppDimens.panelRowHorizontalPadding),
                    singleLine = true,
                    placeholder = {
                        Text(text = stringResource(R.string.search)) 
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.clear)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(composeActionRadius()),
                    textStyle = TextStyle.Default.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = AppDimens.dialogOptionsMaxHeight)
                        .padding(top = AppDimens.panelRowTitleSpacing)
                ) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = AppDimens.scrollbarRailWidth / 2),
                        contentPadding = PaddingValues(bottom = AppDimens.panelRowTitleSpacing)
                    ) {
                        itemsIndexed(filtered, key = { _, index -> index }) { _, originalIndex ->
                            DialogOptionRow(
                                label = options[originalIndex],
                                selected = originalIndex == selectedIndex,
                                onClick = { onSelect(originalIndex) }
                            )
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_empty_title),
                                    modifier = Modifier.padding(
                                        horizontal = AppDimens.panelRowTitleSpacing,
                                        vertical = AppDimens.panelRowTitleSpacing
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        state = listState,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
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

@Composable
private fun DialogOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = AppDimens.panelRowTitleSpacing,
                end = AppDimens.panelRowHorizontalPadding
            )
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(AppDimens.panelRowTitleSpacing))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
