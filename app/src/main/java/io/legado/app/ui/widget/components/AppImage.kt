package io.legado.app.ui.widget.components

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.theme.LegadoTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Compose 侧的非封面图片加载入口（theme-styles.md §7.3）。
 *
 * 书籍 / 分组封面另有取图优先级，走 [AppBookCover]；本组件只负责"给一个地址，显示一张图"，
 * 订阅源图标、书源图标之类都用它。链路与 [AppBookCover] 完全一致：Glide 的 **Drawable**
 * 链路 + 按控件实际尺寸 `override` 降采样 + 离开组合即取消在途请求。
 *
 * @param model 图片地址，支持网络 url / data url / content uri / 本地路径，空值只显示 [placeholderRes]
 * @param contentDescription 无障碍描述；纯装饰图片传 `null`
 * @param placeholderRes 加载中与加载失败的兜底图；传 `null` 时加载期间不绘制任何内容
 * @param errorRes 加载失败专用兜底图，传 `null` 时失败沿用 [placeholderRes]
 * @param sourceOrigin 书源 / 订阅源的来源标识，影响网络请求头与相对路径解析
 * @param cornerRadius 圆角，传 0 表示不裁剪
 * @param contentScale 内容缩放方式，默认 `Crop`（与旧 View 版的 `centerCrop` 一致）
 */
@Composable
fun AppImage(
    modifier: Modifier = Modifier,
    model: String?,
    contentDescription: String?,
    @DrawableRes placeholderRes: Int? = null,
    @DrawableRes errorRes: Int? = null,
    sourceOrigin: String? = null,
    cornerRadius: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val placeholder = remember(placeholderRes) { placeholderRes?.let { context.drawableOf(it) } }
    val error = remember(errorRes) { errorRes?.let { context.drawableOf(it) } }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    // 初值就是占位图：与 View 版的 placeholder 一致，加载期间不会露出背后的壁纸
    var drawable by remember(model, sourceOrigin) { mutableStateOf(placeholder) }

    LaunchedEffect(model, sourceOrigin, bounds) {
        // 等控件测量出尺寸后再发请求，保证 override 的是真实显示尺寸
        if (bounds.width <= 0 || bounds.height <= 0) return@LaunchedEffect
        if (model.isNullOrBlank()) {
            drawable = placeholder
            return@LaunchedEffect
        }
        val loaded = loadImageDrawable(context, model, sourceOrigin, bounds)
        drawable = loaded ?: error ?: placeholder
        // 动图要显式开播：View 版由 ImageView 代劳，Compose 侧没有这一层
        startIfAnimatable(loaded)
    }

    Box(
        modifier = modifier
            .then(
                if (cornerRadius > 0.dp) Modifier.clip(RoundedCornerShape(cornerRadius)) else Modifier
            )
            .onSizeChanged { bounds = it }
    ) {
        val shown = drawable
        if (shown != null) {
            Image(
                painter = remember(shown) { AppDrawablePainter(shown) },
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

/**
 * 按真实显示尺寸加载图片为 [Drawable]。
 *
 * 必须走 Drawable 而不是 Bitmap：Glide 的 `asBitmap()` 对 GIF / 动画 WebP 只解出第一帧。
 * 取消时清掉 target，列表滑走后不再继续解码；`onResourceReady` 与 `onLoadFailed` 可能被先后
 * 调用（后台恢复时 Glide 会重新调度资源），用 [AtomicBoolean] 保证只 resume 一次。
 */
private suspend fun loadImageDrawable(
    context: Context,
    path: String,
    sourceOrigin: String?,
    requestSize: IntSize,
): Drawable? = suspendCancellableCoroutine { cont ->
    // 先在协程存活时取到 RequestManager：取消回调里 Activity 可能已 destroy，
    // 那时再 Glide.with(context) 会抛 "You cannot start a load for a destroyed activity"
    val requestManager = Glide.with(context)
    val resumed = AtomicBoolean(false)
    val target = object : CustomTarget<Drawable>() {
        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
            if (resumed.compareAndSet(false, true) && cont.isActive) {
                cont.resume(resource)
            }
        }

        override fun onLoadCleared(placeholder: Drawable?) = Unit

        override fun onLoadFailed(errorDrawable: Drawable?) {
            if (resumed.compareAndSet(false, true) && cont.isActive) {
                cont.resume(null)
            }
        }
    }
    cont.invokeOnCancellation {
        runCatching { requestManager.clear(target) }
    }
    var options = RequestOptions()
    if (sourceOrigin != null) {
        options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
    }
    ImageLoader.load(context, path)
        .apply(options)
        .centerCrop()
        .override(requestSize.width, requestSize.height)
        .into(target)
}

private fun Context.drawableOf(@DrawableRes resId: Int): Drawable? =
    ContextCompat.getDrawable(this, resId)

/**
 * 把 [Drawable] 画进 Compose 的最小 Painter 实现。
 *
 * Glide 的 Compose 集成只暴露 `GlideImage`，没有可直接复用的 Drawable→Painter；
 * 这里注册 [Drawable.Callback] 接收动图每帧的 `invalidateDrawable` 回调驱动重绘，
 * 静态图则只在换图时重绘。
 */
internal class AppDrawablePainter(private val drawable: Drawable) : Painter() {

    /** 动画帧计数：动图每帧回调递增，读取它即可建立绘制依赖 */
    private var frameTick by mutableIntStateOf(0)

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            frameTick++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, when_: Long) = Unit

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    init {
        drawable.callback = callback
    }

    override val intrinsicSize: Size
        @Suppress("UNUSED_EXPRESSION")
        get() {
            frameTick
            return Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        }

    override fun DrawScope.onDraw() {
        @Suppress("UNUSED_EXPRESSION")
        frameTick
        if (size.width <= 0f || size.height <= 0f) return
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { canvas -> drawable.draw(canvas.nativeCanvas) }
    }
}

/**
 * 动图（GIF / Animated WebP / AnimatedImageDrawable）载入后要显式开始播放：
 * View 版由 ImageView 调 `setVisible(true, true)` 触发，Compose 侧没有这一层，得自己来。
 */
internal fun startIfAnimatable(drawable: Drawable?) {
    val animatable = drawable as? Animatable ?: return
    drawable.setVisible(true, true)
    runCatching { animatable.start() }
}

// ── 预览（navigation-preview.md §10.1 强制）────────────────────────────────

@Preview(name = "占位图", showBackground = true)
@Composable
private fun AppImagePlaceholderPreview() {
    LegadoTheme {
        AppImage(
            modifier = Modifier.size(50.dp),
            model = null,
            contentDescription = null,
            placeholderRes = R.drawable.image_rss,
            cornerRadius = 12.dp,
        )
    }
}
