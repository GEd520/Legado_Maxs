package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.legado.app.R

/**
 * 列表空态占位：内容区居中显示一行提示文案（新版发现 / 新版订阅共用）。
 *
 * 与 [LoadMoreFooter] 的分工：footer 只是列表末尾的一个节点，列表为空时它会贴在顶部，
 * 看起来像"内容压根没渲染出来"——这正是"某个 tab 只有背景"的观感来源。空态需要独立占位
 * 并垂直居中。调用方仅在「列表为空 + 既不在加载也没有出错」时使用它，并让列表不再渲染
 * footer（此时 footer 的文案也是同一句），避免两处提示重复。
 *
 * 自身不消费指针事件，叠加在列表之上时不会挡住滚动与左右滑动手势。
 */
@Composable
fun EmptyContent(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.empty),
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}
