package io.legado.app.ui.widget.components

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.HtmlCoverRenderer
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * 封面请求失败后的重试次数。
 *
 * View 版列表每次条目绑定都会重新发起请求（RecyclerView 复用即重试），Compose 条目只在
 * 首次组合时请求一次：一次失败就永久停在默认封面上，只有滚出屏幕再回来才会重来。
 * 用户反馈的"列表不显示封面、长按弹窗却能看到"正是这个差异（弹窗每次都新发请求）。
 */
private const val COVER_LOAD_ATTEMPTS = 2

/** 两次尝试之间的间隔：给瞬时失败（并发被限速、连接被掐断）留出恢复时间 */
private const val COVER_LOAD_RETRY_DELAY_MS = 1500L

/**
 * 单次封面请求的上限。
 *
 * `OkHttpStreamFetcher` 的读流/解密跑在自己的协程里，抛出的异常会被协程包装吞掉、
 * 不回调 Glide，请求于是永远停在 pending：挂起的加载既不成功也不失败，条目就一直
 * 保持占位封面。超时把这个"静默挂起"转成失败，交给上面的重试。
 */
private const val COVER_LOAD_TIMEOUT_MS = 12_000L

/**
 * 书籍 / 分组封面的 Compose 实现。
 *
 * 与 View 版 `CoverImageView` 保持同一套取图优先级，顺序不能调换：
 * 封面图集默认封面 → HTML 模板封面 → "使用默认封面"开关 → 真实封面图片 → 默认封面；
 * 图片缺失或加载失败时在封面上叠加竖排书名与作者（由封面设置控制）。
 *
 * 加载走 Glide 的 **Drawable** 链路 + 显式 override 尺寸，与首页 `GlideImage`、旧 View 版
 * `CoverImageView` 同一条链路（`asBitmap()` 会丢掉 GIF/WebP 的动画帧）；
 * 组合离开时取消在途请求（`LaunchedEffect` 取消 → `clear` target），不借用 View 版 API。
 *
 * @param name 书名 / 分组名，用于默认封面的竖排书名与 HTML 模板变量
 * @param author 作者，用于默认封面的竖排作者与 HTML 模板变量
 * @param coverPath 已解析的展示封面路径（调用方传 `getDisplayCover()`），可为空
 * @param galleryIdentity 封面图集取图身份：书籍传 bookUrl、分组传 `bookGroup:{分组id}`
 * @param contentDescription 无障碍描述，一般传书名
 * @param sourceOrigin 书源来源标识，影响网络请求头与图集身份回退
 * @param cornerRadius 封面圆角
 * @param loadOnlyWifi 是否只允许 WiFi 下加载网络封面
 */
@Composable
fun AppBookCover(
    modifier: Modifier = Modifier,
    name: String?,
    author: String?,
    coverPath: String?,
    galleryIdentity: String?,
    contentDescription: String?,
    sourceOrigin: String? = null,
    cornerRadius: Dp = AppDimens.bookCoverCornerRadius,
    loadOnlyWifi: Boolean = false,
) {
    val context = LocalContext.current
    // 图集取图是"库查询 + 文件读取"（顺序模式还要回写缓存）：必须按取图身份记忆。
    // 放在组合里裸调会在每次重组时都查一次库，列表滚动时把主线程压满，封面迟迟画不出来
    val galleryCover = remember(galleryIdentity, coverPath) {
        BookCover.getGalleryDefaultCover(galleryIdentity, coverPath)
    }
    val realPath = galleryCover ?: coverPath?.takeIf { it.isNotBlank() }
    // 图集默认封面优先于"强制默认封面"：命中图集时仍显示图集封面
    val useDefaultCover = AppConfig.useDefaultCover && galleryCover == null
    // HTML 模板封面在 View 版里优先于"使用默认封面"，两者顺序不能调换
    val htmlCover = realPath == null && HtmlCoverRenderer.isApplicable(name)
    val drawName = BookCover.drawBookName && !name.isNullOrBlank()
    // 最终显示的是默认封面：既没有真实图片，或用户开启了"使用默认封面"。
    // 此时书名叠加属于"封面本身"的一部分，而不是加载失败的兜底
    val defaultCoverShown = !htmlCover && (realPath == null || useDefaultCover)

    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val requestKey = listOf(realPath, sourceOrigin, htmlCover, useDefaultCover, name, author)
        .joinToString("|")
    // 初值就是默认封面：与 View 版 placeholder(defaultDrawable) 一致，避免加载期间露出壁纸
    var drawable by remember(requestKey) { mutableStateOf(defaultCoverDrawable()) }
    var loadFailed by remember(requestKey) { mutableStateOf(false) }

    LaunchedEffect(requestKey, bounds) {
        // 等控件测量出尺寸后再发请求，保证 override 的是真实显示尺寸
        if (bounds.width <= 0 || bounds.height <= 0) return@LaunchedEffect
        val loaded = when {
            htmlCover -> htmlCoverDrawable(context, name.orEmpty(), author)
            useDefaultCover -> defaultCoverDrawable()
            realPath != null -> loadCoverDrawable(
                context = context,
                path = realPath,
                sourceOrigin = sourceOrigin,
                loadOnlyWifi = loadOnlyWifi,
                requestSize = bounds,
            )

            else -> defaultCoverDrawable()
        }
        // 加载失败回退默认封面（同名叠层由 defaultCoverShown/loadFailed 决定）
        val shown = loaded ?: defaultCoverDrawable()
        drawable = shown
        startIfAnimatable(shown)
        loadFailed = loaded == null
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                if (AppConfig.bookCoverShadow) MaterialTheme.colorScheme.background
                else Color.Transparent
            )
            .onSizeChanged { bounds = it }
    ) {
        val cover = drawable
        if (cover != null) {
            Image(
                painter = remember(cover) { AppDrawablePainter(cover) },
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        // 有真实图片时只在加载失败后叠加书名；默认封面场景则始终叠加
        if (drawName && !htmlCover && (defaultCoverShown || loadFailed)) {
            BookCoverTextOverlay(
                name = name.orEmpty(),
                author = author.orEmpty(),
                drawAuthor = BookCover.drawBookAuthor,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 封面上的竖排书名 / 作者叠层。
 *
 * 书名从左上 20% 位置竖排、作者从右下 95% 位置向上竖排，均带主题背景色描边；
 * 与 View 版 `CoverImageView.generateCoverBitmap` 的绘制参数保持一致。
 */
@Composable
internal fun BookCoverTextOverlay(
    name: String,
    author: String,
    drawAuthor: Boolean,
    modifier: Modifier = Modifier,
) {
    // 描边取主题背景色、文字取强调色，与 View 版 generateCoverBitmap 的取色一致
    val bgColor = MaterialTheme.colorScheme.background.toArgb()
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()

    Canvas(modifier = modifier) {
        val viewWidth = size.width
        val viewHeight = size.height
        val canvas = drawContext.canvas.nativeCanvas

        val namePaint = TextPaint().apply {
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = viewWidth / 7f
            strokeWidth = textSize / 6f
        }

        val nameChars = name.toStringArray()
        var startX = viewWidth * 0.2f
        var startY = viewHeight * 0.2f
        var line = 0
        nameChars.forEachIndexed { index, char ->
            namePaint.color = bgColor
            namePaint.style = Paint.Style.STROKE
            canvas.drawText(char, startX, startY, namePaint)
            namePaint.color = accentColor
            namePaint.style = Paint.Style.FILL
            canvas.drawText(char, startX, startY, namePaint)
            startY += namePaint.textHeight
            if (startY > viewHeight * 0.9f) {
                if ((nameChars.size - index - 1) == 1) {
                    startY -= namePaint.textHeight / 5f
                    namePaint.textSize = viewWidth / 9f
                    return@forEachIndexed
                }
                startX += namePaint.textSize
                line++
                namePaint.textSize = viewWidth / 10f
                startY = viewHeight * 0.2f + namePaint.textHeight * line
            } else if (startY > viewHeight * 0.8f && (nameChars.size - index - 1) > 2) {
                startX += namePaint.textSize
                line++
                namePaint.textSize = viewWidth / 10f
                startY = viewHeight * 0.2f + namePaint.textHeight * line
            }
        }

        if (drawAuthor && author.isNotBlank()) {
            val authorPaint = TextPaint(namePaint).apply {
                typeface = Typeface.DEFAULT
                textSize = viewWidth / 10f
                strokeWidth = textSize / 5f
            }
            val authorChars = author.toStringArray()
            startX = viewWidth * 0.8f
            startY = viewHeight * 0.95f - authorChars.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            authorChars.forEach {
                authorPaint.color = bgColor
                authorPaint.style = Paint.Style.STROKE
                canvas.drawText(it, startX, startY, authorPaint)
                authorPaint.color = accentColor
                authorPaint.style = Paint.Style.FILL
                canvas.drawText(it, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95f) return@forEach
            }
        }
    }
}

/**
 * 加载真实封面图片为 [Drawable]。
 *
 * 必须走 Drawable 而不是 Bitmap：Glide 的 `asBitmap()` 对 GIF/WebP 动图只解出第一帧，
 * 动画信息在目标类型处就丢了。Drawable 链路与旧 View 版 `CoverImageView`、首页 `GlideImage`
 * 一致，动图能正常播放，静态图（含透明 PNG）行为也不变。
 *
 * 单次请求带超时、失败后有界重试，原因见 [COVER_LOAD_TIMEOUT_MS]、[COVER_LOAD_ATTEMPTS]。
 *
 * @param centerCrop 请求是否 centerCrop；瀑布流等自由比例场景传 false，
 *   保持图片原始宽高比（对齐 View 版 CoverLoader 的 fixedRatio = false）
 */
internal suspend fun loadCoverDrawable(
    context: Context,
    path: String,
    sourceOrigin: String?,
    loadOnlyWifi: Boolean,
    requestSize: IntSize,
    centerCrop: Boolean = true,
): Drawable? {
    repeat(COVER_LOAD_ATTEMPTS) { attempt ->
        val loaded = withTimeoutOrNull(COVER_LOAD_TIMEOUT_MS) {
            loadCoverDrawableOnce(context, path, sourceOrigin, loadOnlyWifi, requestSize, centerCrop)
        }
        if (loaded != null) return loaded
        if (attempt < COVER_LOAD_ATTEMPTS - 1) delay(COVER_LOAD_RETRY_DELAY_MS)
    }
    return null
}

/** [loadCoverDrawable] 的单次请求实现 */
private suspend fun loadCoverDrawableOnce(
    context: Context,
    path: String,
    sourceOrigin: String?,
    loadOnlyWifi: Boolean,
    requestSize: IntSize,
    centerCrop: Boolean,
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
    var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
    if (sourceOrigin != null) {
        options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
    }
    var builder = ImageLoader.load(context, path).apply(options)
    if (centerCrop) {
        builder = builder.centerCrop()
    }
    // 高清封面设置开启时不做降采样，与 View 版行为一致；
    // override 返回的是同一个 RequestBuilder（原地修改），这里回写只是让 CheckResult 告警消失
    if (!AppConfig.loadCoverHighQuality) {
        builder = builder.override(requestSize.width, requestSize.height)
    }
    builder.into(target)
}

/**
 * 默认封面 Drawable。
 *
 * [BookCover.defaultDrawable] 由 600x900 的位图或内置 jpg 构造，两种情况下都是 BitmapDrawable，
 * 直接沿用这个实例即可（与 View 版 `placeholder(defaultDrawable)` 取的是同一个对象）。
 */
private fun defaultCoverDrawable(): Drawable? = runCatching { BookCover.defaultDrawable }.getOrNull()

/** HTML 模板封面渲染成位图后包成 Drawable，与真实封面走同一条绘制链路 */
private suspend fun htmlCoverDrawable(context: Context, name: String, author: String?): Drawable? =
    runCatching { HtmlCoverRenderer.load(name, author) }
        .getOrNull()
        ?.let { BitmapDrawable(context.resources, it) }

// ── 预览（navigation-preview.md §10.1 强制）────────────────────────────────

@Preview(name = "无封面（叠加书名）")
@Composable
private fun AppBookCoverPreview() {
    LegadoTheme {
        AppBookCover(
            modifier = Modifier.size(width = 66.dp, height = 88.dp),
            name = "剑来",
            author = "烽火戏诸侯",
            coverPath = null,
            galleryIdentity = null,
            contentDescription = null,
        )
    }
}

@Preview(name = "有封面", showBackground = true)
@Composable
private fun AppBookCoverWithPathPreview() {
    LegadoTheme {
        AppBookCover(
            modifier = Modifier.size(width = 66.dp, height = 88.dp),
            name = "剑来",
            author = "烽火戏诸侯",
            coverPath = "file:///android_asset/preview_cover.jpg",
            galleryIdentity = "preview",
            contentDescription = null,
        )
    }
}
