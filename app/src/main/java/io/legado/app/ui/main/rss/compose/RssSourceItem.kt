package io.legado.app.ui.main.rss.compose

import androidx.compose.runtime.Immutable
import io.legado.app.data.entities.RssSource

/**
 * 订阅页条目模型。
 *
 * 只承载网格渲染与长按菜单需要的字段：搜索、分组过滤、排序仍在 Fragment 侧完成，
 * Compose 侧不做任何数据加工（performance.md §8.1：列表项模型必须 `@Immutable`）。
 */
@Immutable
data class RssSourceItem(
    val sourceUrl: String,
    val sourceName: String,
    val sourceIcon: String,
    /** 没有登录地址的源不显示"登录"菜单项 */
    val hasLoginUrl: Boolean,
)

internal fun List<RssSource>.toRssSourceItems(): List<RssSourceItem> {
    return map { source ->
        RssSourceItem(
            sourceUrl = source.sourceUrl,
            sourceName = source.sourceName,
            sourceIcon = source.sourceIcon,
            hasLoginUrl = !source.loginUrl.isNullOrBlank(),
        )
    }
}
