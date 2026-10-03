package io.legado.app.ui.book.explore.compose

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.LoadMoreFooter
import io.legado.app.ui.widget.components.navigationBarBottomInset
import kotlin.math.abs

/** 一次视口快照：首个可见项下标/偏移、最后一个可见项下标、总项数 */
private data class ExploreViewport(
    val firstIndex: Int,
    val firstOffset: Int,
    val lastIndex: Int,
    val totalCount: Int,
)

/**
 * 发现列表内容区：按布局模式切换 列表 / 网格 / 瀑布流 三种懒加载布局，
 * 并挂接双向翻页、滚动位置缓存与左右滑动切换分类手势。
 *
 * @param bottomPaddingPx 宿主底栏占用的高度（px）：独立发现列表页没有底栏传 0，
 *   主界面内嵌的新版发现由 MainActivity 下发，取它和系统导航栏 inset 的较大者做底部内边距
 */
@Composable
fun ExploreShowListContent(
    controller: ExploreShowController,
    onShowBookInfo: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    modifier: Modifier = Modifier,
    bottomPaddingPx: Int = 0,
) {
    val extraBottomPadding = with(LocalDensity.current) { bottomPaddingPx.toDp() }
    Box(modifier.fillMaxSize()) {
        when (controller.layoutMode) {
            EXPLORE_LAYOUT_WATERFALL -> ExploreShowStaggeredContent(
                controller, onShowBookInfo, onBookLongClick, extraBottomPadding
            )

            EXPLORE_LAYOUT_GRID -> ExploreShowLazyGrid(
                controller, onShowBookInfo, onBookLongClick, extraBottomPadding
            )

            else -> ExploreShowLazyList(
                controller, onShowBookInfo, onBookLongClick, extraBottomPadding
            )
        }
    }
}

/** 网格/瀑布流的列间距：列宽 × 5%，夹在 2..80px（对齐 View 版 calcColumnSpacing） */
@Composable
private fun columnSpacingDp(columnCount: Int): Dp {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(columnCount, configuration.screenWidthDp, density.density) {
        val itemWidthPx =
            configuration.screenWidthDp * density.density / columnCount.coerceAtLeast(1)
        val spacingPx = (itemWidthPx * AppDimens.EXPLORE_SHOW_COLUMN_SPACING_RATIO)
            .toInt()
            .coerceIn(
                AppDimens.exploreShowMinColumnSpacing.value.toInt(),
                AppDimens.exploreShowMaxColumnSpacing.value.toInt()
            )
        with(density) { spacingPx.toDp() }
    }
}

/** 读取条目书架状态；读取 shelfTick 建立依赖，书架变化时触发条目重查（对齐 payload 局部刷新） */
private fun getShelfState(
    controller: ExploreShowController,
    book: SearchBook,
): BookShelfState {
    controller.shelfTick
    return controller.getBookShelfState(book)
}

/** 条目 key：由 controller 维护的"origin+bookUrl+首次出现序号"，追加/前插都保持稳定 */
private fun bookItemKey(controller: ExploreShowController, index: Int): String =
    controller.bookKey(index)

// ── 列表模式 ──

@Composable
private fun ExploreShowLazyList(
    controller: ExploreShowController,
    onShowBookInfo: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    extraBottomPadding: Dp = 0.dp,
) {
    val listState = rememberLazyListState()
    // 主界面内嵌时底栏浮在内容之上：取底栏高度与系统导航栏 inset 的较大者，末项才完整露出
    val bottomInset = maxOf(navigationBarBottomInset, extraBottomPadding)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .exploreCategorySwipe(controller),
        contentPadding = PaddingValues(bottom = bottomInset)
    ) {
        if (controller.topFooter.visible) {
            item(key = "top_footer") {
                LoadMoreFooter(
                    isLoading = controller.topFooter.isLoading,
                    hasMore = controller.topFooter.hasMore,
                    message = controller.topFooter.message,
                    isError = controller.topFooter.isError
                )
            }
        }
        itemsIndexed(controller.books, key = { index, _ -> bookItemKey(controller, index) }) {
                index, book ->
            Column {
                if (index == 0 && controller.topFooter.visible) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ExploreShowListItem(
                    book = book,
                    shelfState = getShelfState(controller, book),
                    onBookClick = { onShowBookInfo(book) },
                    onBookLongClick = { onBookLongClick(book) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item(key = "bottom_footer") {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadNext(forceLoad = true) }
            )
        }
    }

    ExploreViewportEffect(controller) {
        ExploreViewport(
            firstIndex = listState.firstVisibleItemIndex,
            firstOffset = listState.firstVisibleItemScrollOffset,
            lastIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = listState.layoutInfo.totalItemsCount
        )
    }
    ExploreScrollCommandEffect(controller) { command ->
        // 守卫必须用 controller 的数据量：指令消费发生在新数据测量完成之前，
        // layoutInfo.totalItemsCount 此时还是旧值，会导致恢复/跳页定位静默失效
        val topFooterCount = if (controller.topFooter.visible) 1 else 0
        val total = controller.books.size + topFooterCount + 1
        when (command) {
            is ExploreScrollCommand.SkipPageTop ->
                if (total > 2) listState.scrollToItem(1, 0)

            is ExploreScrollCommand.RestoreCategory -> {
                val saved = controller.cachedScrollSnapshot(command.url)
                if (saved != null && saved.index in 1..controller.books.size + topFooterCount) {
                    listState.scrollToItem(saved.index, saved.offset)
                } else {
                    listState.scrollToItem(0, 0)
                }
            }

            is ExploreScrollCommand.PrependAnchor ->
                if (listState.firstVisibleItemIndex <= 1 && command.anchorIndex < total) {
                    listState.scrollToItem(command.anchorIndex, 0)
                }

            is ExploreScrollCommand.RestoreIndex ->
                if (command.index < total) listState.scrollToItem(command.index)
        }
    }
}

// ── 网格模式 ──

@Composable
private fun ExploreShowLazyGrid(
    controller: ExploreShowController,
    onShowBookInfo: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    extraBottomPadding: Dp = 0.dp,
) {
    val columnCount = controller.columnGrid
    val gridState = rememberLazyGridState()
    val spacing = columnSpacingDp(columnCount)
    val halfSpacing = spacing / 2
    val bottomInset = maxOf(navigationBarBottomInset, extraBottomPadding)

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .exploreCategorySwipe(controller),
        contentPadding = PaddingValues(
            start = halfSpacing,
            end = halfSpacing,
            top = halfSpacing,
            bottom = bottomInset
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        if (controller.topFooter.visible) {
            item(key = "top_footer", span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(
                    isLoading = controller.topFooter.isLoading,
                    hasMore = controller.topFooter.hasMore,
                    message = controller.topFooter.message,
                    isError = controller.topFooter.isError
                )
            }
        }
        itemsIndexed(controller.books, key = { index, _ -> bookItemKey(controller, index) }) {
                _, book ->
            ExploreShowGridItem(
                book = book,
                shelfState = getShelfState(controller, book),
                onBookClick = { onShowBookInfo(book) },
                onBookLongClick = { onBookLongClick(book) }
            )
        }
        item(key = "bottom_footer", span = { GridItemSpan(maxLineSpan) }) {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadNext(forceLoad = true) }
            )
        }
    }

    ExploreViewportEffect(controller) {
        ExploreViewport(
            firstIndex = gridState.firstVisibleItemIndex,
            firstOffset = gridState.firstVisibleItemScrollOffset,
            lastIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = gridState.layoutInfo.totalItemsCount
        )
    }
    ExploreScrollCommandEffect(controller) { command ->
        val topFooterCount = if (controller.topFooter.visible) 1 else 0
        val total = controller.books.size + topFooterCount + 1
        when (command) {
            is ExploreScrollCommand.SkipPageTop -> Unit

            is ExploreScrollCommand.RestoreCategory -> {
                val saved = controller.cachedScrollSnapshot(command.url)
                if (saved != null && saved.index in 0 until total) {
                    gridState.scrollToItem(saved.index, saved.offset)
                } else {
                    gridState.scrollToItem(0, 0)
                }
            }

            is ExploreScrollCommand.PrependAnchor -> Unit

            is ExploreScrollCommand.RestoreIndex ->
                if (command.index < total) gridState.scrollToItem(command.index)
        }
    }
}

// ── 瀑布流模式 ──

@Composable
private fun ExploreShowStaggeredContent(
    controller: ExploreShowController,
    onShowBookInfo: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    extraBottomPadding: Dp = 0.dp,
) {
    val columnCount = controller.columnWaterfall
    val staggeredState = rememberLazyStaggeredGridState()
    val spacing = columnSpacingDp(columnCount)
    val halfSpacing = spacing / 2
    val bottomInset = maxOf(navigationBarBottomInset, extraBottomPadding)

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columnCount),
        state = staggeredState,
        modifier = Modifier
            .fillMaxSize()
            .exploreCategorySwipe(controller),
        contentPadding = PaddingValues(
            start = halfSpacing,
            end = halfSpacing,
            top = halfSpacing,
            bottom = bottomInset
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalItemSpacing = spacing
    ) {
        if (controller.topFooter.visible) {
            item(key = "top_footer", span = StaggeredGridItemSpan.FullLine) {
                LoadMoreFooter(
                    isLoading = controller.topFooter.isLoading,
                    hasMore = controller.topFooter.hasMore,
                    message = controller.topFooter.message,
                    isError = controller.topFooter.isError
                )
            }
        }
        itemsIndexed(controller.books, key = { index, _ -> bookItemKey(controller, index) }) {
                _, book ->
            ExploreShowWaterfallItem(
                book = book,
                shelfState = getShelfState(controller, book),
                columnCount = columnCount,
                onBookClick = { onShowBookInfo(book) },
                onBookLongClick = { onBookLongClick(book) }
            )
        }
        item(key = "bottom_footer", span = StaggeredGridItemSpan.FullLine) {
            LoadMoreFooter(
                isLoading = controller.footer.isLoading,
                hasMore = controller.footer.hasMore,
                message = controller.footer.message,
                isError = controller.footer.isError,
                onClick = { controller.requestLoadNext(forceLoad = true) }
            )
        }
    }

    ExploreViewportEffect(controller) {
        ExploreViewport(
            firstIndex = staggeredState.firstVisibleItemIndex,
            firstOffset = staggeredState.firstVisibleItemScrollOffset,
            lastIndex = staggeredState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            totalCount = staggeredState.layoutInfo.totalItemsCount
        )
    }
    ExploreScrollCommandEffect(controller) { command ->
        val topFooterCount = if (controller.topFooter.visible) 1 else 0
        val total = controller.books.size + topFooterCount + 1
        when (command) {
            is ExploreScrollCommand.SkipPageTop -> Unit

            is ExploreScrollCommand.RestoreCategory -> {
                val saved = controller.cachedScrollSnapshot(command.url)
                if (saved != null && saved.index in 0 until total) {
                    staggeredState.scrollToItem(saved.index, saved.offset)
                } else {
                    staggeredState.scrollToItem(0, 0)
                }
            }

            is ExploreScrollCommand.PrependAnchor -> Unit

            is ExploreScrollCommand.RestoreIndex ->
                if (command.index < total) staggeredState.scrollToItem(command.index)
        }
    }
}

// ── 共用副作用 ──

/**
 * 视口上报 → 翻页触发与滚动位置缓存。
 *
 * 用 snapshotFlow 观察替代 View 版的 onScrolled 逐帧回调：停在底部时加载完成、
 * 状态变化都会再次触发（与 View 版行为一致，由 hasMore/loading 与冷却防重）；
 * 差异是首屏不足一屏时也会直接连发加载直到填满（View 版需一次滚动事件）。
 */
@Composable
private fun ExploreViewportEffect(
    controller: ExploreShowController,
    viewport: () -> ExploreViewport,
) {
    LaunchedEffect(controller) {
        snapshotFlow(viewport).collect { value ->
            controller.reportViewport(
                firstIndex = value.firstIndex,
                firstOffset = value.firstOffset,
                lastIndex = value.lastIndex,
                totalCount = value.totalCount
            )
        }
    }
}

/** 滚动指令消费：指令就位（数据已组合）后执行一次 */
@Composable
private fun ExploreScrollCommandEffect(
    controller: ExploreShowController,
    execute: suspend (ExploreScrollCommand) -> Unit,
) {
    LaunchedEffect(controller.scrollCommand) {
        val command = controller.scrollCommand ?: return@LaunchedEffect
        execute(command)
        controller.consumeScrollCommand()
    }
}

/**
 * 左右滑动手势切换分类（对齐 View 版 GestureDetector.onFling）：
 * 水平位移 > 100dp 且大于垂直位移、并达到系统 fling 速度时切换到上/下一个分类。
 * 只观察不消费触摸事件，不影响列表自身滚动与点击。
 */
@Composable
private fun Modifier.exploreCategorySwipe(
    controller: ExploreShowController,
): Modifier {
    val context = LocalContext.current
    return pointerInput(controller.showCategoryTab, controller.kinds.isNotEmpty()) {
    val minSwipePx = AppDimens.exploreShowMinSwipeDistance.toPx()
    val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var totalX = 0f
        var totalY = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: event.changes.firstOrNull()
            if (change == null || !change.pressed) break
            tracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y
            if (!controller.showCategoryTab || controller.kinds.isEmpty()) break
            // 移动过程中判定（不等抬起）：一旦确认水平 fling 立即切换分类并消费
            // 剩余事件——消费会让条目 clickable 的 tap 判定取消，避免切换分类的
            // 同时点开落点处的条目（View 版由 RecyclerView 拦截 press 兜底）
            if (abs(totalX) > minSwipePx && abs(totalX) > abs(totalY)) {
                val velocity = tracker.calculateVelocity()
                if (abs(velocity.x) > minFlingVelocity) {
                    if (totalX > 0) {
                        controller.switchToPreviousCategory()
                    } else {
                        controller.switchToNextCategory()
                    }
                    event.changes.forEach { it.consume() }
                    break
                }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}
}
