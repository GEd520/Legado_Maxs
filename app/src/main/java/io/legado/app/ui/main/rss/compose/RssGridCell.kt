package io.legado.app.ui.main.rss.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.pageSecondaryTextColor

/**
 * 名称字号与行高。
 *
 * 对齐原 `tv_name` 的 13sp；行高必须显式给：只传 `fontSize` 时 Compose 会继承
 * `bodyLarge` 的 24sp 行高，两行名称之间会隔出一大截（同书架条目的处理）。
 */
private const val RSS_GRID_NAME_TEXT_SIZE = 13
private const val RSS_GRID_NAME_LINE_HEIGHT = 16

/**
 * 订阅页网格单元：居中的图标 + 下方最多两行居中的名称。
 *
 * 图标由调用方以插槽传入——订阅源图标走 [io.legado.app.ui.widget.components.AppImage]，
 * "规则订阅"入口用本地资源图，两者的加载链路不同但排版一致。
 *
 * 无障碍以名称文字为准（语义已合并），图标一律当作装饰，不需要额外描述。
 *
 * @param name 单元名称，超长时省略到两行
 * @param onClick 整格点击
 * @param onLongClick 整格长按（无长按行为的单元用默认空实现）
 * @param icon 图标插槽，尺寸与圆角由调用方决定
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RssGridCell(
    modifier: Modifier = Modifier,
    name: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(AppDimens.rssGridItemPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(
            text = name,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppDimens.rssGridNameSpacing),
            color = pageSecondaryTextColor(),
            fontSize = RSS_GRID_NAME_TEXT_SIZE.sp,
            lineHeight = RSS_GRID_NAME_LINE_HEIGHT.sp,
            textAlign = TextAlign.Center,
            // 不给 minLines：原 tv_name 是 wrap_content，单行名称的格子本来就矮，
            // 补齐两行会让所有格子一样高、网格多出空白
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
