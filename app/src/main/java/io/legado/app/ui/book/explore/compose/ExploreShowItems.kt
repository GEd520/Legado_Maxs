package io.legado.app.ui.book.explore.compose

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.HtmlCoverRenderer
import io.legado.app.model.BookCover
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.AppBookCover
import io.legado.app.ui.widget.components.AppDrawablePainter
import io.legado.app.ui.widget.components.BookCoverTextOverlay
import io.legado.app.ui.widget.components.loadCoverDrawable
import io.legado.app.ui.widget.components.startIfAnimatable

/** 书架状态小绿点：固定色来自 res 的 md_green_600，与主题无关的既定品牌色 */
private val SHELF_DOT_GREEN = Color(0xFF43A047)

/** 瀑布流卡片底色/描边：对齐 card_bg_water / card_border_water 的固定半透明色 */
private val WATERFALL_FILL = Color.White.copy(alpha = AppDimens.EXPLORE_SHOW_WATERFALL_FILL_ALPHA)
private val WATERFALL_BORDER = Color.Black.copy(alpha = AppDimens.EXPLORE_SHOW_WATERFALL_BORDER_ALPHA)

/** 书架状态角标（对齐 View 版 setShelfState：IN_SHELF→对勾，SAME_NAME_AUTHOR→乱序图标） */
@Composable
fun ExploreShelfBadgeIcon(
    state: BookShelfState,
    modifier: Modifier = Modifier,
) {
    if (AppConfig.bookshelfIconStyle != 0) return
    val res = when (state) {
        BookShelfState.IN_SHELF -> R.drawable.ic_check
        BookShelfState.SAME_NAME_AUTHOR -> R.drawable.ic_shuffle
        else -> return
    }
    Box(
        modifier
            .size(AppDimens.exploreShowShelfBadgeSize)
            .background(
                MaterialTheme.colorScheme.background,
                RoundedCornerShape(AppDimens.exploreShowShelfBadgeCornerRadius)
            )
            .padding(AppDimens.exploreShowShelfBadgePadding)
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
        )
    }
}

/** 书架状态小绿点（对齐 View 版 setShelfStateDot），仅经典图标样式下显示 */
@Composable
fun ExploreShelfStateDot(
    state: BookShelfState,
    dotSize: Dp,
    modifier: Modifier = Modifier,
) {
    if (AppConfig.bookshelfIconStyle != 1) return
    val show = state == BookShelfState.IN_SHELF || state == BookShelfState.SAME_NAME_AUTHOR
    if (!show) return
    Box(modifier.size(dotSize).background(SHELF_DOT_GREEN, CircleShape))
}

/** 分类标签胶囊（对齐 LabelsBar 的 AccentBgTextView：强调色底 + 2dp 圆角 + 12sp） */
@Composable
fun ExploreKindLabels(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.exploreShowLabelSpacing)
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(AppDimens.exploreShowLabelCornerRadius)
                    )
                    .padding(horizontal = AppDimens.exploreShowLabelPaddingHorizontal),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** SearchBook 的封面图集身份（对齐 CoverImageView.load(SearchBook) 的拼接顺序） */
private fun SearchBook.galleryIdentity(): String =
    listOf(bookUrl, origin, name, author).joinToString("|")

/**
 * 列表模式条目（对齐 item_search：80x110 封面 + 书名/作者/标签/最新章节/简介）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExploreShowListItem(
    book: SearchBook,
    shelfState: BookShelfState,
    onBookClick: () -> Unit,
    onBookLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onBookClick, onLongClick = onBookLongClick)
            .padding(AppDimens.exploreShowItemPadding)
    ) {
        Box {
            AppBookCover(
                modifier = Modifier.size(
                    width = AppDimens.exploreShowListCoverWidth,
                    height = AppDimens.exploreShowListCoverHeight
                ),
                name = book.name,
                author = book.author,
                coverPath = book.coverUrl,
                galleryIdentity = book.galleryIdentity(),
                contentDescription = book.name,
                sourceOrigin = book.origin,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi
            )
            ExploreShelfBadgeIcon(
                shelfState,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppDimens.exploreShowShelfBadgeMargin)
            )
        }
        Column(
            Modifier
                .padding(start = AppDimens.exploreShowItemPadding)
                .height(AppDimens.exploreShowListCoverHeight)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExploreShelfStateDot(
                    shelfState,
                    AppDimens.exploreShowShelfDotSizeList,
                    Modifier.padding(end = AppDimens.exploreShowShelfBadgeMargin)
                )
                Text(
                    text = book.name,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(AppDimens.exploreShowRowSpacing))
            Text(
                text = context.getString(R.string.author_show, book.author),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ExploreKindLabels(book.getKindList())
            if (!book.latestChapterTitle.isNullOrEmpty()) {
                Text(
                    text = context.getString(R.string.lasted_show, book.latestChapterTitle),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = book.trimIntro(context),
                modifier = Modifier.weight(1f, fill = true),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 网格模式条目（对齐 item_explore_show_grid：3:4 封面 + 居中书名）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExploreShowGridItem(
    book: SearchBook,
    shelfState: BookShelfState,
    onBookClick: () -> Unit,
    onBookLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onBookClick,
                onLongClick = onBookLongClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            )
    ) {
        Box {
            AppBookCover(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / AppDimens.BOOK_COVER_ASPECT),
                name = book.name,
                author = book.author,
                coverPath = book.coverUrl,
                galleryIdentity = book.galleryIdentity(),
                contentDescription = book.name,
                sourceOrigin = book.origin,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi
            )
            ExploreShelfBadgeIcon(
                shelfState,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppDimens.exploreShowShelfBadgeMargin)
            )
        }
        Row(
            Modifier.padding(top = AppDimens.exploreShowGridNameSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExploreShelfStateDot(
                shelfState,
                AppDimens.exploreShowShelfDotSizeSmall,
                Modifier.padding(end = AppDimens.exploreShowShelfDotSpacing)
            )
            Text(
                text = book.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 瀑布流模式条目（对齐 item_explore_show_waterfall：自由比例封面 + 完整信息卡片）。
 *
 * @param columnCount 当前列数，用于按卡片宽度折算简介最大行数（对齐 View 版规则）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExploreShowWaterfallItem(
    book: SearchBook,
    shelfState: BookShelfState,
    columnCount: Int,
    onBookClick: () -> Unit,
    onBookLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(WATERFALL_FILL)
            .border(AppDimens.exploreShowWaterfallBorderWidth, WATERFALL_BORDER)
            .combinedClickable(
                onClick = onBookClick,
                onLongClick = onBookLongClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            )
            .padding(bottom = AppDimens.exploreShowWaterfallPadding)
    ) {
        Box {
            ExploreWaterfallCover(
                book = book,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                modifier = Modifier.fillMaxWidth()
            )
            ExploreShelfBadgeIcon(
                shelfState,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppDimens.exploreShowShelfBadgeMargin)
            )
        }
        Column(Modifier.padding(horizontal = AppDimens.exploreShowWaterfallPadding)) {
            Row(
                Modifier.padding(top = AppDimens.exploreShowWaterfallPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExploreShelfStateDot(
                    shelfState,
                    AppDimens.exploreShowShelfDotSizeSmall,
                    Modifier.padding(end = AppDimens.exploreShowShelfDotSpacing)
                )
                Text(
                    text = book.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = book.author,
                modifier = Modifier.padding(top = AppDimens.exploreShowRowSpacing),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ExploreKindLabels(
                book.getKindList(),
                Modifier.padding(top = AppDimens.exploreShowRowSpacing)
            )
            if (!book.latestChapterTitle.isNullOrEmpty()) {
                Text(
                    text = book.latestChapterTitle.orEmpty(),
                    modifier = Modifier.padding(top = AppDimens.exploreShowRowSpacing),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = book.intro?.trim().orEmpty(),
                modifier = Modifier.padding(top = AppDimens.exploreShowRowSpacing),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                maxLines = waterfallIntroMaxLines(columnCount),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 简介最大行数：≤3 列 10 行，否则按卡片宽度（360dp 基准 4 行）折算，1..6 行 */
@Composable
private fun waterfallIntroMaxLines(columnCount: Int): Int {
    if (columnCount <= 3) return 10
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(columnCount, configuration.screenWidthDp, density.density) {
        val spacingPx = ((configuration.screenWidthDp * density.density / columnCount)
            * AppDimens.EXPLORE_SHOW_COLUMN_SPACING_RATIO)
            .toInt()
            .coerceIn(
                AppDimens.exploreShowMinColumnSpacing.value.toInt(),
                AppDimens.exploreShowMaxColumnSpacing.value.toInt()
            )
        val itemWidthDp = configuration.screenWidthDp / columnCount - spacingPx / density.density
        (itemWidthDp / 360f * 4).toInt().coerceIn(1, 6)
    }
}

/**
 * 瀑布流自由比例封面（对齐 CoverLoader.load(fixedRatio = false)）：
 * 图集默认封面 → HTML 模板封面 → "使用默认封面"开关 → 真实封面 → 无路径时绘书名，
 * 显示比例跟随加载结果的真实宽高；请求按"宽 × 宽×4/3"降采样但不裁剪比例。
 */
@Composable
fun ExploreWaterfallCover(
    book: SearchBook,
    loadOnlyWifi: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cleanName = book.name?.replace(AppPattern.bdRegex, "")?.trim()
    val cleanAuthor = book.author?.replace(AppPattern.bdRegex, "")?.trim()
    val galleryCover = remember(book.galleryIdentity(), book.coverUrl) {
        BookCover.getGalleryDefaultCover(book.galleryIdentity(), book.coverUrl)
    }
    val realPath = galleryCover ?: book.coverUrl?.takeIf { it.isNotBlank() }
    val useDefaultCover = AppConfig.useDefaultCover && galleryCover == null
    val htmlCover = galleryCover == null && cleanName != null && HtmlCoverRenderer.isApplicable(cleanName)
    // 无封面路径且开启绘名：View 版在此画"透明底 + 竖排书名"位图
    val drawNameOverlay = galleryCover == null && !htmlCover && !useDefaultCover &&
        BookCover.drawBookName && !cleanName.isNullOrBlank() && realPath == null
    val requestKey = listOf(realPath, book.origin, htmlCover, useDefaultCover, cleanName, cleanAuthor)
        .joinToString("|")

    // 占位图：与 View 版 placeholder 一致，加载完成前显示默认封面（绘名封面对除外）
    var drawable by remember(requestKey) {
        mutableStateOf<Drawable?>(if (drawNameOverlay) null else defaultCoverDrawable())
    }
    // 高度/宽度比：默认封面 600x900；绘名封面对齐 CoverLoader 用 4:3
    var heightRatio by remember(requestKey) { mutableFloatStateOf(1.5f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(requestKey, bounds) {
        if (bounds.width <= 0) return@LaunchedEffect
        when {
            drawNameOverlay -> {
                heightRatio = AppDimens.BOOK_COVER_ASPECT
                drawable = null
            }

            htmlCover -> {
                val bitmap = runCatching {
                    HtmlCoverRenderer.load(cleanName.orEmpty(), cleanAuthor)
                }.getOrNull()
                val loaded = bitmap?.let { BitmapDrawable(context.resources, it) }
                if (loaded != null) {
                    drawable = loaded
                    val iw = loaded.intrinsicWidth
                    val ih = loaded.intrinsicHeight
                    if (iw > 0 && ih > 0) heightRatio = ih.toFloat() / iw
                } else {
                    drawable = defaultCoverDrawable()
                }
            }

            useDefaultCover -> drawable = defaultCoverDrawable()

            realPath != null -> {
                val loaded = loadCoverDrawable(
                    context = context,
                    path = realPath,
                    sourceOrigin = book.origin,
                    loadOnlyWifi = loadOnlyWifi,
                    requestSize = IntSize(bounds.width, bounds.width * 4 / 3),
                    centerCrop = false
                )
                if (loaded != null) {
                    drawable = loaded
                    startIfAnimatable(loaded)
                    val iw = loaded.intrinsicWidth
                    val ih = loaded.intrinsicHeight
                    if (iw > 0 && ih > 0) heightRatio = ih.toFloat() / iw
                } else {
                    drawable = defaultCoverDrawable()
                }
            }

            else -> drawable = defaultCoverDrawable()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f / heightRatio)
            .onSizeChanged { bounds = it }
    ) {
        val shown = drawable
        if (shown != null) {
            Image(
                painter = remember(shown) { AppDrawablePainter(shown) },
                contentDescription = book.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (drawNameOverlay) {
            BookCoverTextOverlay(
                name = cleanName.orEmpty(),
                author = cleanAuthor.orEmpty(),
                drawAuthor = BookCover.drawBookAuthor,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun defaultCoverDrawable(): Drawable? =
    runCatching { BookCover.defaultDrawable }.getOrNull()
