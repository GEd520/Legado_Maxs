package io.legado.app.ui.main.rss.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.legado.app.ui.widget.components.VerticalScrollbar

/** 网格列数，对齐原 fragment_rss.xml 里 RecyclerView 的 spanCount=4 */
private const val RSS_GRID_COLUMN_COUNT = 4

/** "规则订阅"入口的稳定 key：它与订阅源同处一个网格，但既不过滤也不排序 */
private const val RSS_RULE_SUB_KEY = "rss_rule_subscription"

/**
 * 订阅页的订阅源网格。
 *
 * 只负责渲染与滚动：数据（搜索 / 分组过滤 / 排序后的结果）由 Fragment 下发，
 * 因此在 Compose 侧不做任何排序过滤（performance.md §8.1）。
 * "规则订阅"入口固定在首格，其余依次为订阅源，与 View 版的头部条目行为一致。
 */
@Composable
internal fun RssSourceGrid(
    modifier: Modifier = Modifier,
    sourceItems: List<RssSourceItem>,
    bottomPaddingPx: Int,
    actions: RssSourceActions,
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    // 底栏浮在内容之上：末行由单元格自身的 16dp 内边距与底栏隔开，这里只补底栏高度
    val bottomPadding = remember(bottomPaddingPx, density) {
        with(density) { bottomPaddingPx.toDp() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(RSS_GRID_COLUMN_COUNT),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item(key = RSS_RULE_SUB_KEY) {
                RssRuleSubEntry(onClick = actions.onOpenRuleSub)
            }
            items(items = sourceItems, key = { it.sourceUrl }) { sourceItem ->
                RssSourceGridItem(
                    sourceItem = sourceItem,
                    actions = actions,
                )
            }
        }
        VerticalScrollbar(
            state = gridState,
            bottomInset = bottomPadding,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}
