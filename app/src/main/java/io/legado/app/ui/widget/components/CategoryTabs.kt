package io.legado.app.ui.widget.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import io.legado.app.ui.theme.AppDimens
import kotlin.math.ceil

/**
 * 多行分类标签栏（发现列表页与新版订阅共用，复刻 View 版 setupMultiLineTabs）：
 * 最多 3 行（横屏最多 2 行），每行可横向滚动、内容不足时居中；
 * 选中 Tab 显示强调色描边，切换后自动滚入所在行的视野。
 *
 * 与 View 版一致，点击过的 Tab 文字色转为次要色（保留原实现的视觉反馈）。
 *
 * @param titles 分类标题列表（下标即业务下标）
 * @param selectedIndex 当前选中下标
 * @param onSelect 选中回调（携带全局下标）
 */
@Composable
fun CategoryTabs(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val orientation = LocalConfiguration.current.orientation
    val rowCount = remember(titles.size, orientation) {
        var rows = when {
            titles.size <= 10 -> 1
            titles.size <= 20 -> 2
            else -> 3
        }
        // 横屏最多 2 行
        if (rows > 1 && orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            rows--
        }
        rows
    }
    val maxPerRow = ceil(titles.size / rowCount.toDouble()).toInt().coerceAtLeast(1)
    // keyed by titles：换源/换分类集后点击态不残留到同下标的新 Tab
    val clickedIndexes = remember(titles) { mutableStateListOf<Int>() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 对齐 View 版 tabs_container：无背景，透出页面壁纸
            .padding(
                horizontal = AppDimens.exploreShowTabsHorizontalPadding,
                vertical = AppDimens.exploreShowTabsVerticalPadding
            )
    ) {
        titles.chunked(maxPerRow).forEachIndexed { rowIndex, rowItems ->
            val rowState = rememberScrollState()
            val rowWidth = remember { mutableIntStateOf(0) }
            val tabBounds = remember { mutableStateMapOf<Int, IntRect>() }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rowState)
                    .onSizeChanged { rowWidth.intValue = it.width },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowItems.forEachIndexed { indexInRow, title ->
                    val globalIndex = rowIndex * maxPerRow + indexInRow
                    CategoryTab(
                        title = title,
                        selected = globalIndex == selectedIndex,
                        clicked = globalIndex in clickedIndexes,
                        onSelect = {
                            clickedIndexes.add(globalIndex)
                            onSelect(globalIndex)
                        },
                        onBoundsChanged = { tabBounds[globalIndex] = it },
                        modifier = Modifier.padding(end = AppDimens.exploreShowTabSpacing)
                    )
                }
            }

            // 选中 Tab 滚入视野（对齐 View 版 ensureTabVisible）
            val density = LocalDensity.current
            LaunchedEffect(selectedIndex, tabBounds.size) {
                if (selectedIndex / maxPerRow != rowIndex) return@LaunchedEffect
                val bounds = tabBounds[selectedIndex] ?: return@LaunchedEffect
                val paddingPx = with(density) {
                    AppDimens.exploreShowTabVisiblePadding.toPx()
                }.toInt()
                val target = when {
                    bounds.left - paddingPx < rowState.value ->
                        bounds.left - paddingPx

                    bounds.right + paddingPx > rowState.value + rowWidth.intValue ->
                        bounds.right - rowWidth.intValue + paddingPx

                    else -> null
                }
                target?.let { rowState.animateScrollTo(it.coerceIn(0, rowState.maxValue)) }
            }

            // 行间距：只有非最后一行才有底部间距
            if (rowIndex < rowCount - 1) {
                Spacer(Modifier.height(AppDimens.exploreShowTabRowSpacing))
            }
        }
    }
}

@Composable
private fun CategoryTab(
    title: String,
    selected: Boolean,
    clicked: Boolean,
    onSelect: () -> Unit,
    onBoundsChanged: (IntRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppDimens.exploreShowTabCornerRadius)
    Text(
        text = title,
        modifier = modifier
            // 记录 Tab 在所在行内的偏移与尺寸（供选中后滚入视野计算）
            .onGloballyPositioned { coords ->
                val pos = coords.positionInParent()
                onBoundsChanged(IntRect(IntOffset(pos.x.toInt(), pos.y.toInt()), coords.size))
            }
            .then(
                if (selected) {
                    Modifier.border(AppDimens.exploreShowTabBorderWidth, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(
                horizontal = AppDimens.exploreShowTabHorizontalPadding,
                vertical = AppDimens.exploreShowTabVerticalPadding
            ),
        color = if (clicked) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onBackground
        },
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
