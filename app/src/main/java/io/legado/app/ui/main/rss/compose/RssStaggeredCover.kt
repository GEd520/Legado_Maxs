package io.legado.app.ui.main.rss.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.CoverAspectRatioCache
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.components.AppDrawablePainter
import io.legado.app.ui.widget.components.loadCoverDrawable
import io.legado.app.ui.widget.components.startIfAnimatable

/** 加载中/未加载时的占位底色 */
private val StaggeredCoverPlaceholder = Color.Transparent

/**
 * 瀑布流（articleStyle 3）条目的自由比例图片：
 * 显示比例跟随图片真实宽高（对齐 View 版 adjustViewBounds），请求按"宽×宽×4/3"
 * 降采样但不裁剪；加载完成前显示占位底色。
 */
@Composable
internal fun RssStaggeredCover(
    article: RssArticle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model = article.image
    var drawable by remember(model, article.origin) {
        mutableStateOf<Drawable?>(null)
    }
    // 高度/宽度比：优先取缓存过的真实比例——条目滑出视口被回收后重新滑回来时，
    // 用默认比例起手会等图片加载完再改高度，整列跟着重排（表现就是"往上滑书籍跳动"）；
    // 没有缓存才按 4:3 占位，加载完成后再取真实比例
    var heightRatio by remember(model, article.origin) {
        mutableFloatStateOf(CoverAspectRatioCache.get(model).takeIf { it > 0f } ?: 4f / 3f)
    }
    var bounds by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(model, article.origin, bounds) {
        if (bounds.width <= 0) return@LaunchedEffect
        if (model.isNullOrBlank()) return@LaunchedEffect
        val loaded = loadCoverDrawable(
            context = context,
            path = model,
            sourceOrigin = article.origin,
            loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
            requestSize = IntSize(bounds.width, bounds.width * 4 / 3),
            centerCrop = false
        )
        if (loaded != null) {
            drawable = loaded
            startIfAnimatable(loaded)
            val iw = loaded.intrinsicWidth
            val ih = loaded.intrinsicHeight
            if (iw > 0 && ih > 0) {
                val ratio = ih.toFloat() / iw
                heightRatio = ratio
                CoverAspectRatioCache.put(model, ratio)
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f / heightRatio)
            .onSizeChanged { bounds = it }
            .background(StaggeredCoverPlaceholder)
    ) {
        val shown = drawable
        if (shown != null) {
            Image(
                painter = remember(shown) { AppDrawablePainter(shown) },
                contentDescription = article.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
