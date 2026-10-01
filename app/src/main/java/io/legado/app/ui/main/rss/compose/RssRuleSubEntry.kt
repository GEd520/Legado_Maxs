package io.legado.app.ui.main.rss.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens

/**
 * 订阅页网格首格的"规则订阅"入口。
 *
 * 对应 View 版挂在适配器上的头部条目（固定在首位，不参与搜索过滤与排序），
 * 图标是本地资源，因此不走 Glide。
 */
@Composable
internal fun RssRuleSubEntry(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val name = stringResource(R.string.rule_subscription)
    RssGridCell(
        modifier = modifier,
        name = name,
        contentDescription = name,
        onClick = onClick,
        icon = {
            Image(
                painter = painterResource(R.drawable.image_legado),
                contentDescription = null,
                modifier = Modifier
                    .size(AppDimens.rssGridIconSize)
                    .clip(RoundedCornerShape(AppDimens.rssGridIconCornerRadius)),
            )
        },
    )
}
