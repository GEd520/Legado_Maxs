package io.legado.app.ui.main.rss.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.AppImage
import io.legado.app.ui.widget.components.dialog.AppRadioChoiceDialog
import io.legado.app.ui.widget.components.BlockProgressChip
import io.legado.app.ui.widget.components.CategoryTabs
import io.legado.app.ui.widget.components.LoadMoreFooter
import io.legado.app.ui.widget.components.navigationBarBottomInset

/**
 * 新版订阅：在订阅主 Tab 内直接显示"源选择行 + 分类标签栏 + 文章列表"，
 * 省掉"订阅源网格 → 点进源"的两级跳转（参考 Legado_R 新版订阅）。
 *
 * 菜单动作由宿主 Fragment 的 TitleBar 三点菜单承担；列表布局支持 列表 / 双列网格。
 *
 * @param controller 页面状态
 * @param sources 启用的订阅源列表（Flow 实时数据）
 * @param showBlockProgress 是否显示屏蔽进度芯片
 * @param onReadArticle 点击文章
 * @param onSelectSource 选中源回调（宿主负责持久化与触发重新加载）
 */
@Composable
fun ModernRssContent(
    controller: ModernRssController,
    sources: List<RssSource>,
    showBlockProgress: Boolean,
    onReadArticle: (RssArticle) -> Unit,
    onSelectSource: (RssSource) -> Unit,
    onShowBlockRule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSourcePicker by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        ModernRssSourceRow(
            sources = sources,
            selectedSourceUrl = controller.source?.sourceUrl,
            onPickSource = { showSourcePicker = true },
            modifier = Modifier.fillMaxWidth()
        )
        if (controller.sorts.size > 1) {
            CategoryTabs(
                titles = controller.sorts.map { it.first },
                selectedIndex = controller.selectedSortIndex,
                onSelect = { controller.selectSort(it) }
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (controller.gridMode) {
                ModernRssGrid(controller = controller, onReadArticle = onReadArticle)
            } else {
                ModernRssList(controller = controller, onReadArticle = onReadArticle)
            }
            if (showBlockProgress && controller.blockedCount > 0) {
                BlockProgressChip(
                    blockedCount = controller.blockedCount,
                    onClick = onShowBlockRule,
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
            title = stringResource(R.string.rss),
            options = sources.map { it.sourceName },
            selectedIndex = sources.indexOfFirst { it.sourceUrl == controller.source?.sourceUrl },
            onSelect = { index ->
                showSourcePicker = false
                sources.getOrNull(index)?.let(onSelectSource)
            },
            onDismissRequest = { showSourcePicker = false }
        )
    }
}

/** 源选择行：当前源名的胶囊按钮，点击弹出源选择弹窗 */
@Composable
private fun ModernRssSourceRow(
    sources: List<RssSource>,
    selectedSourceUrl: String?,
    onPickSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentName = sources
        .firstOrNull { it.sourceUrl == selectedSourceUrl }
        ?.sourceName
        ?: sources.firstOrNull()?.sourceName
        ?: stringResource(R.string.rss)

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

@Composable
private fun ModernRssList(
    controller: ModernRssController,
    onReadArticle: (RssArticle) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = navigationBarBottomInset)
    ) {
        items(controller.articles, key = { "${it.origin}_${it.link}_${it.sort}" }) { article ->
            Column {
                ModernRssListItem(
                    article = article,
                    onReadArticle = { onReadArticle(article) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item(key = "footer") {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadMore(forceLoad = true) }
            )
        }
    }
    ModernRssPagingEffect(controller) {
        ModernRssViewport(
            firstIndex = listState.firstVisibleItemIndex,
            lastIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = listState.layoutInfo.totalItemsCount
        )
    }
}

@Composable
private fun ModernRssGrid(
    controller: ModernRssController,
    onReadArticle: (RssArticle) -> Unit,
) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = AppDimens.rssGridItemPadding / 2,
            end = AppDimens.rssGridItemPadding / 2,
            bottom = navigationBarBottomInset
        ),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.rssGridItemPadding / 2),
        verticalArrangement = Arrangement.spacedBy(AppDimens.rssGridItemPadding / 2)
    ) {
        items(controller.articles, key = { "${it.origin}_${it.link}_${it.sort}" }) { article ->
            ModernRssGridItem(
                article = article,
                onReadArticle = { onReadArticle(article) }
            )
        }
        item(key = "footer", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadMore(forceLoad = true) }
            )
        }
    }
    ModernRssPagingEffect(controller) {
        ModernRssViewport(
            firstIndex = gridState.firstVisibleItemIndex,
            lastIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = gridState.layoutInfo.totalItemsCount
        )
    }
}

/** 一次视口快照（底部触达即加载下一页，对齐 View 版 onScrolled） */
private data class ModernRssViewport(val firstIndex: Int, val lastIndex: Int, val totalCount: Int)

@Composable
private fun ModernRssPagingEffect(
    controller: ModernRssController,
    viewport: () -> ModernRssViewport,
) {
    LaunchedEffect(controller) {
        snapshotFlow(viewport).collect { value ->
            if (value.totalCount > 0 && value.lastIndex >= value.totalCount - 1) {
                controller.requestLoadMore()
            }
        }
    }
}

/** 列表条目：对齐 item_rss_article（标题 16sp + 时间 12sp + 右侧 110x68 图） */
@Composable
private fun ModernRssListItem(
    article: RssArticle,
    onReadArticle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReadArticle)
            .padding(horizontal = AppDimens.rssGridItemPadding, vertical = 16.dp)
            .height(68.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = article.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatRssDate(article.pubDate),
                modifier = Modifier.padding(top = AppDimens.exploreShowRowSpacing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!article.image.isNullOrBlank()) {
            AppImage(
                model = article.image,
                contentDescription = null,
                sourceOrigin = article.origin,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .height(68.dp)
                    .aspectRatio(110f / 68f)
                    .clip(RoundedCornerShape(AppDimens.bookCoverCornerRadius))
            )
        }
    }
}

/** 网格条目：对齐 item_rss_article_1（大图 + 标题 15sp + 时间 11sp） */
@Composable
private fun ModernRssGridItem(
    article: RssArticle,
    onReadArticle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onReadArticle)
    ) {
        if (!article.image.isNullOrBlank()) {
            AppImage(
                model = article.image,
                contentDescription = null,
                sourceOrigin = article.origin,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        }
        Text(
            text = article.title,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatRssDate(article.pubDate),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatRssDate(pubDate: String?): String {
    // RSS 源里的 pubDate 是源规则抓到的原始字符串，直接展示（与 View 版一致）
    return pubDate.orEmpty()
}
