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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import io.legado.app.ui.widget.components.dialog.AppSearchableChoiceDialog
import io.legado.app.ui.widget.components.dialog.AppTagGridDialog
import io.legado.app.ui.widget.components.BlockProgressChip
import io.legado.app.ui.widget.components.LoadMoreFooter
import io.legado.app.ui.widget.components.ModernTagBar
import io.legado.app.ui.widget.components.navigationBarBottomInset

/**
 * 新版订阅：在订阅主 Tab 内直接显示"源切换 + 分类标签栏 + 文章列表"，
 * 省掉"订阅源网格 → 点进源"的两级跳转（对齐 Legado_R 新版订阅的结构）。
 *
 * 头部行在 TitleBar 正下方左上角：源名（20sp 粗体）+ ▾ 下拉切换源，
 * 右侧功能菜单（三点）承担分类页的全部菜单项；列表布局支持
 * articleStyle 0-4 五态（列表 / 单列大图 / 双列网格 / 瀑布流 / 三列网格，
 * 对齐 View 版 switchLayout 循环）。
 *
 * @param controller 页面状态
 * @param sources 启用的订阅源列表（Flow 实时数据）
 * @param showBlockProgress 是否显示屏蔽进度芯片
 * @param onReadArticle 点击文章
 * @param onSelectSource 选中源回调（宿主负责持久化与触发重新加载）
 * @param onSwitchLegacy 切换回旧版订阅
 */
/** 新版订阅头部三点菜单的一项（id 与 Fragment 的处理逻辑对应） */
data class RssMoreMenuItem(
    val id: Int,
    val title: String,
    val visible: Boolean = true,
)

/** 分类/分组展开弹窗的数量阈值（对齐参考分支与发现页的 EXPAND_THRESHOLD） */
private const val TAG_EXPAND_THRESHOLD = 12

@Composable
fun ModernRssContent(
    controller: ModernRssController,
    sources: List<RssSource>,
    showBlockProgress: Boolean,
    onReadArticle: (RssArticle) -> Unit,
    onSelectSource: (RssSource) -> Unit,
    onShowBlockRule: () -> Unit,
    moreMenuItems: () -> List<RssMoreMenuItem>,
    onMenuItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showSortPicker by remember { mutableStateOf(false) }
    // 分类条里的展开按钮阈值：与发现页同款（≥12 项时出现 ▾）
    val sortTitles = controller.sorts.map { it.first }

    Column(modifier.fillMaxSize()) {
        ModernRssSourceRow(
            sources = sources,
            selectedSourceUrl = controller.source?.sourceUrl,
            onPickSource = { showSourcePicker = true },
            moreMenuItems = moreMenuItems,
            onMenuItem = onMenuItem,
            modifier = Modifier.fillMaxWidth()
        )
        if (sortTitles.size > 1) {
            // 与新版发现同款的横滚胶囊标签条（选中描边，多分类时 ▾ 展开）
            ModernTagBar(
                items = sortTitles,
                selectedIndex = controller.selectedSortIndex,
                onSelect = { controller.selectSort(it) },
                showExpand = sortTitles.size >= TAG_EXPAND_THRESHOLD,
                onExpand = { showSortPicker = true }
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (controller.articleStyle) {
                1 -> ModernRssLargeCardList(controller, onReadArticle)
                2 -> ModernRssGrid(controller, onReadArticle)
                3 -> ModernRssStaggered(controller, onReadArticle)
                4 -> ModernRssCompactGrid(controller, onReadArticle)
                else -> ModernRssList(controller, onReadArticle)
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
        AppSearchableChoiceDialog(
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

    if (showSortPicker) {
        AppTagGridDialog(
            title = stringResource(R.string.select),
            options = sortTitles,
            selectedIndex = controller.selectedSortIndex,
            onSelect = { index ->
                showSortPicker = false
                controller.selectSort(index)
            },
            onDismissRequest = { showSortPicker = false }
        )
    }
}

/**
 * 头部行：左上角"源名 ▾"（20sp 粗体，点击切换源），右侧功能菜单（三点，
 * Compose DropdownMenu 锚定按钮）圆钮——对齐参考分支 ll_rss_source_row。
 */
@Composable
private fun ModernRssSourceRow(
    sources: List<RssSource>,
    selectedSourceUrl: String?,
    onPickSource: () -> Unit,
    moreMenuItems: () -> List<RssMoreMenuItem>,
    onMenuItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentName = sources
        .firstOrNull { it.sourceUrl == selectedSourceUrl }
        ?.sourceName
        ?: sources.firstOrNull()?.sourceName
        ?: stringResource(R.string.rss)
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .statusBarsPadding()
            // 自占旧版 TitleBar 的高度（状态栏 inset + 56dp toolbar），内容垂直居中
            .height(AppDimens.topBarHeight)
            .padding(horizontal = AppDimens.exploreShowTabsHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onPickSource),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentName,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(R.string.rss),
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .padding(start = AppDimens.exploreShowTabSpacing)
                    .size(28.dp)
            )
        }
        Box {
            androidx.compose.material3.IconButton(onClick = { showMoreMenu = true }) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.more),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
            ) {
                moreMenuItems().filter { it.visible }.forEach { item ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(text = item.title) },
                        onClick = {
                            showMoreMenu = false
                            onMenuItem(item.id)
                        }
                    )
                }
            }
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
        contentPadding = PaddingValues(bottom = navigationBarBottomInset)
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
    ModernRssScrollResetEffect(controller) { listState.scrollToItem(0) }
    ModernRssPagingEffect(controller) {
        ModernRssViewport(
            firstIndex = listState.firstVisibleItemIndex,
            lastIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = listState.layoutInfo.totalItemsCount
        )
    }
}

/** 单列大图条目列表（对齐 articleStyle 1 / item_rss_article_1：图高 220dp + 标题 15sp） */
@Composable
private fun ModernRssLargeCardList(
    controller: ModernRssController,
    onReadArticle: (RssArticle) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = navigationBarBottomInset)
    ) {
        items(controller.articles, key = { "${it.origin}_${it.link}_${it.sort}" }) { article ->
            ModernRssLargeCardItem(
                article = article,
                onReadArticle = { onReadArticle(article) }
            )
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
    ModernRssScrollResetEffect(controller) { listState.scrollToItem(0) }
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
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
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
        item(key = "footer", span = { GridItemSpan(2) }) {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadMore(forceLoad = true) }
            )
        }
    }
    ModernRssScrollResetEffect(controller) { gridState.scrollToItem(0) }
    ModernRssPagingEffect(controller) {
        ModernRssViewport(
            firstIndex = gridState.firstVisibleItemIndex,
            lastIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = gridState.layoutInfo.totalItemsCount
        )
    }
}

/** 三列网格（对齐 articleStyle 4 / item_rss_article_4：图高 182dp + 标题 13sp） */
@Composable
private fun ModernRssCompactGrid(
    controller: ModernRssController,
    onReadArticle: (RssArticle) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppDimens.rssGridItemPadding / 2,
            end = AppDimens.rssGridItemPadding / 2,
            bottom = navigationBarBottomInset
        ),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.rssGridItemPadding / 2),
        verticalArrangement = Arrangement.spacedBy(AppDimens.rssGridItemPadding / 2)
    ) {
        items(controller.articles, key = { "${it.origin}_${it.link}_${it.sort}" }) { article ->
            ModernRssCompactItem(
                article = article,
                onReadArticle = { onReadArticle(article) }
            )
        }
        item(key = "footer", span = { GridItemSpan(3) }) {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadMore(forceLoad = true) }
            )
        }
    }
    ModernRssScrollResetEffect(controller) { gridState.scrollToItem(0) }
    ModernRssPagingEffect(controller) {
        ModernRssViewport(
            firstIndex = gridState.firstVisibleItemIndex,
            lastIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = gridState.layoutInfo.totalItemsCount
        )
    }
}

/** 瀑布流（对齐 articleStyle 3：StaggeredGrid 2 列，图自由比例） */
@Composable
private fun ModernRssStaggered(
    controller: ModernRssController,
    onReadArticle: (RssArticle) -> Unit,
) {
    val staggeredState = rememberLazyStaggeredGridState()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = staggeredState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppDimens.rssGridItemPadding / 2,
            end = AppDimens.rssGridItemPadding / 2,
            bottom = navigationBarBottomInset
        ),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.rssGridItemPadding / 2),
        verticalItemSpacing = AppDimens.rssGridItemPadding / 2
    ) {
        items(controller.articles, key = { "${it.origin}_${it.link}_${it.sort}" }) { article ->
            ModernRssStaggeredItem(
                article = article,
                onReadArticle = { onReadArticle(article) }
            )
        }
        item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadMore(forceLoad = true) }
            )
        }
    }
    ModernRssScrollResetEffect(controller) {
        staggeredState.scrollToItem(0)
    }
    ModernRssPagingEffect(controller) {
        ModernRssViewport(
            firstIndex = staggeredState.firstVisibleItemIndex,
            lastIndex = staggeredState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = staggeredState.layoutInfo.totalItemsCount
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

/** 跳页/换分类后的滚动复位 */
@Composable
private fun ModernRssScrollResetEffect(
    controller: ModernRssController,
    reset: suspend () -> Unit,
) {
    LaunchedEffect(controller.scrollToTopTick) {
        if (controller.scrollToTopTick > 0) reset()
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

/** 单列大图条目（对齐 item_rss_article_1：图高 220dp + 标题 15sp 两行 + 时间 11sp） */
@Composable
private fun ModernRssLargeCardItem(
    article: RssArticle,
    onReadArticle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReadArticle)
    ) {
        if (!article.image.isNullOrBlank()) {
            AppImage(
                model = article.image,
                contentDescription = article.title,
                sourceOrigin = article.origin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppDimens.rssModernCardImageHeight)
            )
        }
        Text(
            text = article.title,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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

/** 双列网格条目（对齐 articleStyle 2：16:9 图 + 标题 15sp + 时间 11sp） */
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
                contentDescription = article.title,
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

/** 三列网格条目（对齐 item_rss_article_4：图高 182dp + 标题 13sp 两行 + 时间 11sp） */
@Composable
private fun ModernRssCompactItem(
    article: RssArticle,
    onReadArticle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReadArticle)
    ) {
        if (!article.image.isNullOrBlank()) {
            AppImage(
                model = article.image,
                contentDescription = article.title,
                sourceOrigin = article.origin,
                placeholderRes = R.drawable.image_rss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppDimens.rssModernCompactImageHeight)
            )
        } else {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.image_rss),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppDimens.rssModernCompactImageHeight)
            )
        }
        Text(
            text = article.title,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatRssDate(article.pubDate),
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 瀑布流条目（对齐 item_rss_article_3：自由比例图 + 标题 13sp + 时间 11sp） */
@Composable
private fun ModernRssStaggeredItem(
    article: RssArticle,
    onReadArticle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onReadArticle)
    ) {
        RssStaggeredCover(article = article)
        Text(
            text = article.title,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatRssDate(article.pubDate),
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
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
