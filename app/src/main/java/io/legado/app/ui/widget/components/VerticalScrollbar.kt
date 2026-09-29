package io.legado.app.ui.widget.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import io.legado.app.ui.theme.AppDimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 停止滚动后拖柄淡出的等待时长。
 *
 * 这是参考分支 NG_main 书籍目录滚动块的显隐节奏（业务节奏，不属于动画时长档位），
 * 不是可以随手调小的过渡值：太大显得滚动条赖着不走，太小会在惯性滚动间隙闪断。
 */
private const val ScrollbarHideDelayMillis = 1_000L

// ==================== LazyListState ====================

/**
 * 通用的可拖拽垂直滚动条。
 *
 * 外观与显隐节奏对齐参考分支 NG_main 书籍目录的快速滚动块：细轨道 + 主题色短拖柄；
 * 内容不可滚动时完全不显示，滚动中或拖拽中显示、停止 [ScrollbarHideDelayMillis] 后淡出，
 * 淡出后不再拦截触摸。尺寸见 [AppDimens] 的 scrollbar* 令牌，颜色取自 [MaterialTheme]。
 *
 * 用法：将滚动条和内容放在同一个 Box 中，滚动条对齐右侧。
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     val state = rememberLazyListState()
 *     LazyColumn(state = state, modifier = Modifier.fillMaxSize()) { ... }
 *     VerticalScrollbar(state = state, modifier = Modifier.align(Alignment.CenterEnd))
 * }
 * ```
 *
 * 另有 [LazyGridState] 与 [ScrollState] 两个重载，行为一致。
 *
 * @param state 被控制内容的滚动状态，拖拽按比例反过来驱动它
 * @param modifier 施加在拖拽感应区上的修饰符，调用点通常只做 `align(Alignment.CenterEnd)`
 */
@Composable
fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val canScroll by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.visibleItemsInfo.isNotEmpty() && info.totalItemsCount > info.visibleItemsInfo.size
        }
    }
    val scrollFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visibleItems = info.visibleItemsInfo
            val first = visibleItems.firstOrNull()
            // 分母取"首个可见项能达到的最大下标"，滚到底时刚好是 1
            val maxFirstIndex = (info.totalItemsCount - visibleItems.size).coerceAtLeast(1)
            if (first == null || first.size <= 0) {
                0f
            } else {
                val itemOffset = (-first.offset).toFloat() / first.size
                ((first.index + itemOffset) / maxFirstIndex).coerceIn(0f, 1f)
            }
        }
    }
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    ScrollbarThumb(
        scrollFraction = scrollFraction,
        canScroll = canScroll,
        isScrollInProgress = state.isScrollInProgress,
        onScrollFractionChange = { fraction ->
            val info = state.layoutInfo
            val totalItems = info.totalItemsCount
            if (totalItems > 0) {
                // 分母与上面显示用的保持一致，拖柄才会停在手指所在的位置
                val maxFirstIndex = (totalItems - info.visibleItemsInfo.size).coerceAtLeast(1)
                val index = (fraction * maxFirstIndex).roundToInt().coerceIn(0, totalItems - 1)
                scrollJob?.cancel()
                scrollJob = scope.launch { state.scrollToItem(index) }
            }
        },
        modifier = modifier
    )
}

// ==================== LazyGridState ====================

@Composable
fun VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier
) {
    val canScroll by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.visibleItemsInfo.isNotEmpty() && info.totalItemsCount > info.visibleItemsInfo.size
        }
    }
    val scrollFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visibleItems = info.visibleItemsInfo
            // 网格按行滚动：首个可见项所在行的行高就是插值单位
            val first = visibleItems.firstOrNull()
            val maxFirstIndex = (info.totalItemsCount - visibleItems.size).coerceAtLeast(1)
            if (first == null || first.size.height <= 0) {
                0f
            } else {
                val itemOffset = (-first.offset.y).toFloat() / first.size.height
                ((first.index + itemOffset) / maxFirstIndex).coerceIn(0f, 1f)
            }
        }
    }
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    ScrollbarThumb(
        scrollFraction = scrollFraction,
        canScroll = canScroll,
        isScrollInProgress = state.isScrollInProgress,
        onScrollFractionChange = { fraction ->
            val info = state.layoutInfo
            val totalItems = info.totalItemsCount
            if (totalItems > 0) {
                val maxFirstIndex = (totalItems - info.visibleItemsInfo.size).coerceAtLeast(1)
                val index = (fraction * maxFirstIndex).roundToInt().coerceIn(0, totalItems - 1)
                scrollJob?.cancel()
                scrollJob = scope.launch { state.scrollToItem(index) }
            }
        },
        modifier = modifier
    )
}

// ==================== ScrollState ====================

@Composable
fun VerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier
) {
    val canScroll by remember(state) {
        derivedStateOf { state.maxValue > 0 }
    }
    val scrollFraction by remember(state) {
        derivedStateOf {
            if (state.maxValue > 0) {
                (state.value.toFloat() / state.maxValue).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    ScrollbarThumb(
        scrollFraction = scrollFraction,
        canScroll = canScroll,
        isScrollInProgress = state.isScrollInProgress,
        onScrollFractionChange = { fraction ->
            val maxValue = state.maxValue
            if (maxValue > 0) {
                scrollJob?.cancel()
                scrollJob = scope.launch { state.scrollTo((fraction * maxValue).roundToInt()) }
            }
        },
        modifier = modifier
    )
}

// ==================== 共享 Thumb ====================

@Composable
private fun ScrollbarThumb(
    scrollFraction: Float,
    canScroll: Boolean,
    isScrollInProgress: Boolean,
    onScrollFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    // 显隐节奏：不能滚动就永不出现；滚动中/拖拽中立即出现；静止满 ScrollbarHideDelayMillis 后淡出
    LaunchedEffect(canScroll, isScrollInProgress, dragging) {
        when {
            !canScroll -> visible = false
            isScrollInProgress || dragging -> visible = true
            else -> {
                delay(ScrollbarHideDelayMillis)
                visible = false
            }
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = SpringSpec(stiffness = Spring.StiffnessMediumLow),
        label = "VerticalScrollbarAlpha"
    )
    val currentOnScrollFractionChange by rememberUpdatedState(onScrollFractionChange)
    val density = LocalDensity.current
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    val handleColor = MaterialTheme.colorScheme.primary
    var railHeightPx by remember { mutableStateOf(0f) }
    val paddingPx = with(density) { AppDimens.scrollbarVerticalPadding.toPx() }
    val thumbHeightPx = with(density) { AppDimens.scrollbarThumbHeight.toPx() }
    val travelPx = (railHeightPx - paddingPx * 2f - thumbHeightPx).coerceAtLeast(1f)

    Box(
        modifier = modifier
            .width(AppDimens.scrollbarRailWidth)
            .fillMaxHeight()
            .onSizeChanged { railHeightPx = it.height.toFloat() }
            .then(
                // 淡出期间不吃触摸，避免挡住内容右缘的点击与滑动
                if (visible && canScroll) {
                    Modifier.pointerInput(canScroll, railHeightPx) {
                        // 手指绝对位置映射到滚动比例：轨道顶部 = 开头，底部 = 结尾
                        fun scrollTo(positionY: Float) {
                            val fraction = ((positionY - paddingPx - thumbHeightPx / 2f) / travelPx)
                                .coerceIn(0f, 1f)
                            currentOnScrollFractionChange(fraction)
                        }

                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                scrollTo(it.y)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onDrag = { change, _ ->
                                change.consume()
                                scrollTo(change.position.y)
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // 只在拿到真实高度后绘制，避免首帧用 0 计算偏移
        if (railHeightPx > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(vertical = AppDimens.scrollbarVerticalPadding)
                    .width(AppDimens.scrollbarTrackWidth)
                    .fillMaxHeight()
                    .background(trackColor.copy(alpha = AppDimens.SCROLLBAR_TRACK_ALPHA * alpha))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (paddingPx + travelPx * scrollFraction.coerceIn(0f, 1f)).roundToInt()
                        )
                    }
                    .width(AppDimens.scrollbarThumbWidth)
                    .height(AppDimens.scrollbarThumbHeight)
                    .background(handleColor.copy(alpha = alpha))
            )
        }
    }
}
