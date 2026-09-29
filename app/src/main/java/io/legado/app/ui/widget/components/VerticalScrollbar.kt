package io.legado.app.ui.widget.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.AppDimens
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 停止滚动后拖柄淡出的等待时长。
 *
 * 这是参考分支 NG_main 目录抽屉滚动块的显隐节奏（业务节奏，不属于动画时长档位），
 * 不是可以随手调小的过渡值：太大显得滚动条赖着不走，太小会在惯性滚动间隙闪断。
 */
private const val ScrollbarHideDelayMillis = 1_000L

// ==================== LazyListState ====================

/**
 * 通用的可拖拽垂直滚动条。
 *
 * 外观与显隐节奏对齐参考分支 NG_main 阅读页目录抽屉的快速滚动块（FLOATING_HANDLE 变体）：
 * 无常驻轨道，只有一块圆角浮动拖柄、内含上下箭头；内容不可滚动时完全不显示，
 * 滚动中或拖拽中显示、停止 [ScrollbarHideDelayMillis] 后淡出，淡出后不再拦截触摸。
 * 尺寸见 [AppDimens] 的 scrollbar* 令牌，颜色取自 [MaterialTheme]。
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
    val canScroll = state.canScrollForward || state.canScrollBackward
    // 像素口径：内容总高与已滚距离都用"可见项平均高度"估算，两者同口径，
    // 所以列表项高度参差、可见项数量一帧一变时比例几乎不动。
    // 若改成"项下标 ÷（总数 − 可见项数）"，可见项数在滚动中反复变化会让拖柄上下乱跳
    // （列表项高矮不一时尤其明显，网格里部分行列进出更严重）。
    val scrollFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val first = visible.firstOrNull()
            if (first == null || visible.isEmpty() || info.viewportSize.height <= 0) return@derivedStateOf 0f
            val avgItemHeight = visible.sumOf { it.size }.toFloat() / visible.size
            if (avgItemHeight <= 0f) return@derivedStateOf 0f
            val maxScroll = avgItemHeight * info.totalItemsCount - info.viewportSize.height
            if (maxScroll <= 0f) return@derivedStateOf 0f
            val scrolled = first.index * avgItemHeight - first.offset - info.beforeContentPadding
            (scrolled / maxScroll).coerceIn(0f, 1f)
        }
    }
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    ScrollbarHandle(
        scrollFraction = scrollFraction,
        canScroll = canScroll,
        isScrollInProgress = state.isScrollInProgress,
        onScrollFractionChange = { fraction ->
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isNotEmpty() && info.totalItemsCount > 0) {
                val avgItemHeight = visible.sumOf { it.size }.toFloat() / visible.size
                val maxScroll = avgItemHeight * info.totalItemsCount - info.viewportSize.height
                if (avgItemHeight > 0f && maxScroll > 0f) {
                    // 与显示同一口径反算下标，拖柄才会停在手指所在的位置
                    val index = ((fraction * maxScroll + info.beforeContentPadding) / avgItemHeight)
                        .roundToInt()
                        .coerceIn(0, info.totalItemsCount - 1)
                    scrollJob?.cancel()
                    scrollJob = scope.launch { state.scrollToItem(index) }
                }
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
    val canScroll = state.canScrollForward || state.canScrollBackward
    // 网格要按"行"算：item 下标每行跳列数次，直接用下标会让拖柄一行一行地窜，
    // 所以先用首行列数换出行下标，再把行高与行数当像素口径。
    val scrollFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val first = visible.firstOrNull()
            if (first == null || visible.isEmpty() || info.viewportSize.height <= 0) return@derivedStateOf 0f
            val columns = columnsInFirstRow(info.visibleItemsInfo)
            val avgLineHeight = visible.sumOf { it.size.height }.toFloat() / visible.size
            if (avgLineHeight <= 0f) return@derivedStateOf 0f
            val lineCount = ceil(info.totalItemsCount / columns.toFloat()).toInt()
            val maxScroll = avgLineHeight * lineCount - info.viewportSize.height
            if (maxScroll <= 0f) return@derivedStateOf 0f
            val lineIndex = first.index / columns
            val scrolled = lineIndex * avgLineHeight - first.offset.y - info.beforeContentPadding
            (scrolled / maxScroll).coerceIn(0f, 1f)
        }
    }
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    ScrollbarHandle(
        scrollFraction = scrollFraction,
        canScroll = canScroll,
        isScrollInProgress = state.isScrollInProgress,
        onScrollFractionChange = { fraction ->
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isNotEmpty() && info.totalItemsCount > 0) {
                val columns = columnsInFirstRow(visible)
                val avgLineHeight = visible.sumOf { it.size.height }.toFloat() / visible.size
                val lineCount = ceil(info.totalItemsCount / columns.toFloat()).toInt()
                val maxScroll = avgLineHeight * lineCount - info.viewportSize.height
                if (avgLineHeight > 0f && maxScroll > 0f) {
                    val lineIndex = ((fraction * maxScroll + info.beforeContentPadding) / avgLineHeight)
                        .roundToInt()
                        .coerceAtLeast(0)
                    val index = (lineIndex * columns).coerceIn(0, info.totalItemsCount - 1)
                    scrollJob?.cancel()
                    scrollJob = scope.launch { state.scrollToItem(index) }
                }
            }
        },
        modifier = modifier
    )
}

/** 首行可见项数即列数：同一行的项 offset.y 相同，且 visibleItemsInfo 按下标升序。 */
private fun columnsInFirstRow(visibleItems: List<LazyGridItemInfo>): Int {
    val first = visibleItems.firstOrNull() ?: return 1
    return visibleItems.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
}

// ==================== ScrollState ====================

@Composable
fun VerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier
) {
    val canScroll = state.maxValue > 0
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
    ScrollbarHandle(
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

// ==================== 共享拖柄 ====================

@Composable
private fun ScrollbarHandle(
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
    val isDark = AppConfig.isNightTheme
    val isEInk = AppConfig.isEInkMode
    val primary = MaterialTheme.colorScheme.primary
    // 底色对齐参考实现的取色口径：浅色主题用白、深色主题用比背景更亮的容器色
    // （本仓库深色主题的 surfaceContainer* 是往黑调、比书架卡片还暗，拖柄会糊在卡片里，
    // 所以这里取 surfaceVariant 这档"往上调"的容器色），再掺一点主色避免死灰
    val baseColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color.White
    val containerColor = Color(
        ColorUtils.blendColors(
            baseColor.toArgb(),
            primary.toArgb(),
            if (isDark) AppDimens.SCROLLBAR_HANDLE_BLEND_DARK else AppDimens.SCROLLBAR_HANDLE_BLEND_LIGHT
        )
    ).copy(
        alpha = when {
            isEInk -> 1f
            isDark -> AppDimens.SCROLLBAR_HANDLE_ALPHA_DARK
            else -> AppDimens.SCROLLBAR_HANDLE_ALPHA_LIGHT
        }
    )
    val borderColor = primary.copy(
        alpha = if (isEInk) {
            AppDimens.SCROLLBAR_HANDLE_BORDER_ALPHA_EINK
        } else {
            AppDimens.SCROLLBAR_HANDLE_BORDER_ALPHA
        }
    )
    val shadowColor = Color.Black.copy(
        alpha = if (isDark) {
            AppDimens.SCROLLBAR_HANDLE_SHADOW_ALPHA_DARK
        } else {
            AppDimens.SCROLLBAR_HANDLE_SHADOW_ALPHA_LIGHT
        }
    )
    val handleShape = RoundedCornerShape(AppDimens.scrollbarHandleCornerRadius)
    var railHeightPx by remember { mutableStateOf(0f) }
    val paddingPx = with(density) { AppDimens.scrollbarVerticalPadding.toPx() }
    val handleHeightPx = with(density) { AppDimens.scrollbarHandleHeight.toPx() }
    val travelPx = (railHeightPx - paddingPx * 2f - handleHeightPx).coerceAtLeast(1f)

    Box(
        modifier = modifier
            .width(AppDimens.scrollbarRailWidth)
            .fillMaxHeight()
            .onSizeChanged { railHeightPx = it.height.toFloat() }
            .then(
                // 淡出后不吃触摸，避免挡住内容右缘的点击与滑动
                if (visible && canScroll) {
                    Modifier.pointerInput(canScroll, railHeightPx) {
                        // 手指绝对位置映射到滚动比例：轨道顶部 = 开头，底部 = 结尾
                        fun scrollTo(positionY: Float) {
                            val fraction = ((positionY - paddingPx - handleHeightPx / 2f) / travelPx)
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
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (paddingPx + travelPx * scrollFraction.coerceIn(0f, 1f)).roundToInt()
                        )
                    }
                    .width(AppDimens.scrollbarHandleWidth)
                    .height(AppDimens.scrollbarHandleHeight)
                    .graphicsLayer { this.alpha = alpha }
                    .shadow(
                        elevation = if (isEInk) {
                            AppDimens.scrollbarHandleShadowElevationEInk
                        } else {
                            AppDimens.scrollbarHandleShadowElevation
                        },
                        shape = handleShape,
                        clip = false,
                        ambientColor = shadowColor,
                        spotColor = shadowColor
                    )
                    .clip(handleShape)
                    .background(containerColor)
                    .border(
                        width = AppDimens.scrollbarHandleBorderWidth,
                        color = borderColor,
                        shape = handleShape
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(AppDimens.scrollbarChevronSize),
                    tint = primary.copy(alpha = AppDimens.SCROLLBAR_HANDLE_ICON_ALPHA)
                )
                Box(Modifier.height(AppDimens.scrollbarChevronSpacing))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(AppDimens.scrollbarChevronSize),
                    tint = primary.copy(alpha = AppDimens.SCROLLBAR_HANDLE_ICON_ALPHA)
                )
            }
        }
    }
}
