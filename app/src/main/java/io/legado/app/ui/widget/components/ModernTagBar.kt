package io.legado.app.ui.widget.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens

/**
 * 新版发现/订阅共用的横滚胶囊标签条（对齐参考分支 RoundedTagBarView 的观感）：
 * 选中项主色描边，末尾可带展开按钮（选项多时点开网格选择弹窗）。
 *
 * @param items 标签文案
 * @param selectedIndex 选中下标；-1 表示无选中
 * @param onSelect 点击标签回调
 * @param showExpand 是否显示末尾的展开按钮
 * @param onExpand 展开按钮点击回调
 */
@Composable
fun ModernTagBar(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    showExpand: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppDimens.exploreShowTabsHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.exploreShowTabSpacing)
        ) {
            itemsIndexed(items, key = { index, title -> "${index}_$title" }) { index, title ->
                val selected = index == selectedIndex
                Surface(
                    shape = RoundedCornerShape(AppDimens.exploreShowTabCornerRadius),
                    color = if (selected) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = if (selected) {
                        BorderStroke(
                            AppDimens.exploreShowTabBorderWidth,
                            MaterialTheme.colorScheme.primary
                        )
                    } else {
                        null
                    },
                    onClick = { onSelect(index) }
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.padding(
                            horizontal = AppDimens.exploreShowTabHorizontalPadding,
                            vertical = AppDimens.exploreShowTabVerticalPadding
                        ),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (showExpand) {
            IconButton(onClick = onExpand, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}
