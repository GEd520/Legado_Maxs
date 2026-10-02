package io.legado.app.ui.book.explore.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens

/**
 * 发现列表的"加载更多"footer，复刻 View 版 LoadMoreView 的语义：
 * 加载中显示转圈；出错显示"加载失败 + 点击查看详情"，点击弹错误弹窗（带重试）；
 * 到底显示文案，点击可强制加载下一页；其余状态不占内容（仅保留布局高度）。
 *
 * @param state footer 状态
 * @param onClick 文本区点击（重试/强制加载）；顶部翻页 footer 对齐 View 版不传
 */
@Composable
fun ExploreLoadMoreFooter(
    state: ExploreLoadMoreState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var showErrorDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.isLoading -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(AppDimens.exploreShowLoadMoreSpacing)
                    .size(AppDimens.exploreShowLoadMoreSize),
                strokeWidth = AppDimens.exploreShowLoadMoreStrokeWidth,
                color = MaterialTheme.colorScheme.primary
            )

            state.isError -> Text(
                text = stringResource(R.string.error_load_msg, stringResource(R.string.error_view_detail)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showErrorDialog = true }
                    .padding(AppDimens.exploreShowLoadMorePadding),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            !state.hasMore -> Text(
                text = state.message ?: stringResource(R.string.bottom_line),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onClick() }
                        } else {
                            Modifier
                        }
                    )
                    .padding(AppDimens.exploreShowLoadMorePadding),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(state.message.orEmpty()) },
            confirmButton = {
                if (onClick != null) {
                    TextButton(onClick = {
                        showErrorDialog = false
                        onClick()
                    }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
