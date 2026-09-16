package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.StateSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.MenuExtensions
import io.legado.app.utils.applyTint
import java.io.File

/**
 * TitleBar 顶栏配置应用扩展函数集。
 *
 * 将 [TopBarConfig] 的配置应用到 TitleBar 控件及其子 View（TabLayout、SearchView）。
 * 根据配置样式（默认/常规）、E-Ink 模式、透明导航栏等条件，
 * 分别设置背景色/壁纸/圆角、文字颜色、标签栏颜色和搜索框样式。
 */

fun TitleBar.applyTopBarConfig() {
    if (skipTopBarConfig) return
    if (AppConfig.isEInkMode) {
        setBackgroundResource(R.drawable.bg_eink_border_bottom)
        applyTopBarContentColor()
        applyTopBarChildConfig()
        return
    }
    if (!opaque && context.transparentNavBar) {
        setBackgroundColor(Color.TRANSPARENT)
        applyTopBarContentColor()
        applyTransparentTopBarChildConfig()
        return
    }
    if (ignoreTopBarOpacity) {
        setBackgroundColor(context.primaryColor)
        applyTopBarContentColor()
        elevation = context.elevation
        applyTopBarChildConfig()
        return
    }
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    applyTopBarConfig(config)
}

private fun TitleBar.applyTopBarConfig(config: TopBarConfig.Config) {
    val backgroundColor = if (config.style == TopBarConfig.STYLE_REGULAR) {
        TopBarConfig.resolveBackgroundColor(config)
    } else {
        config.tagBarColor ?: context.primaryColor
    }
    val radius = if (config.style == TopBarConfig.STYLE_REGULAR) {
        context.resources.getDimension(R.dimen.ui_panel_radius) *
            TopBarConfig.resolveCornerScale(config).coerceIn(0f, 3f)
    } else {
        0f
    }
    val backgroundAlpha = if (config.style == TopBarConfig.STYLE_REGULAR) {
        config.wallpaperAlpha
    } else {
        config.tagBarAlpha
    }
    val shape = regularBackground(
        backgroundColor,
        radius,
        backgroundAlpha,
    )
    val wallpaper = TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
        ?.takeIf { config.style == TopBarConfig.STYLE_REGULAR }
        ?.let { file -> bitmapLayer(file, backgroundAlpha, radius) }
    background = if (wallpaper == null) {
        shape
    } else {
        LayerDrawable(arrayOf(shape, wallpaper))
    }
    elevation = when {
        backgroundAlpha < 100 -> 0.1f
        else -> context.elevation
    }
    val contentColor = topBarContentColor()
    applyTopBarContentColor(contentColor)
    applyTopBarChildConfig(config, contentColor)
}

/** 应用顶栏内容颜色（文字、图标着色） */
private fun TitleBar.applyTopBarContentColor(contentColor: Int = topBarContentColor()) {
    setTextColor(contentColor)
    setColorFilter(contentColor)
    toolbar.menu.applyTint(context, topBarTheme, contentColor)
}

/**
 * 顶栏内容颜色。
 * Auto 模式下按顶栏实际背景（TopBarConfig 纯色/半透明背景与页面背景合成）的亮度决定深浅，
 * 避免主题主色与顶栏背景色调不一致时文字看不清（与二级标签栏的取色思路一致）；
 * 背景含壁纸等无法判定亮度的图层时回退到 getMenuColor 的默认逻辑。
 */
private fun TitleBar.topBarContentColor(): Int {
    if (topBarTheme != Theme.Auto) {
        return MenuExtensions.getMenuColor(context, topBarTheme)
    }
    background.resolveSolidColor()
        ?.compositeOverPageBackground(context)
        ?.let { bgColor ->
            // 背景越亮文字越深：亮背景(>0.5)用深色文字，暗背景用浅色文字。
            // 与 MenuExtensions.getMenuColor 透明导航栏分支的取色约定保持一致。
            return context.getPrimaryTextColor(
                ColorUtils.calculateLuminance(bgColor) > 0.5,
            )
        }
    return MenuExtensions.getMenuColor(context, topBarTheme)
}

/** 解析背景中的纯色：含壁纸等非纯色图层时返回 null（无法判定亮度） */
private fun Drawable?.resolveSolidColor(): Int? {
    if (this == null) return null
    return when (this) {
        is ColorDrawable -> color
        is GradientDrawable -> resolveGradientSolidColor()
        is LayerDrawable -> {
            var resolved: Int? = null
            for (i in 0 until numberOfLayers) {
                resolved = getDrawable(i).resolveSolidColor() ?: return null
            }
            resolved
        }
        else -> null
    }
}

/**
 * GradientDrawable.getColor() 为 API 24 新增，函数级守卫满足 lint NewApi 检查；
 * 低版本无法读取纯色，回退 null 让 topBarContentColor 走 getMenuColor 的默认取色
 */
private fun GradientDrawable.resolveGradientSolidColor(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
    return color?.defaultColor?.takeIf { it != Color.TRANSPARENT }
}

/** 半透明背景色与页面背景合成，得到实际显示的底色 */
private fun Int.compositeOverPageBackground(context: Context): Int = if (Color.alpha(this) == 255) {
    this
} else {
    ColorUtils.compositeColors(this, context.backgroundColor)
}

/** 应用顶栏子 View 配置（TabLayout、SearchView），使用当前 TopBarConfig */
fun View.applyTopBarChildConfig() {
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    val contentColor = if (context.transparentNavBar) {
        MenuExtensions.getMenuColor(context)
    } else {
        context.primaryTextColor
    }
    applyTopBarChildConfig(config, contentColor)
}

/** 生成常规样式的圆角背景 Drawable */
private fun regularBackground(color: Int, radius: Float, alphaPercent: Int): Drawable = GradientDrawable().apply {
    setColor(TopBarConfig.withOpacity(color, alphaPercent))
    cornerRadii = if (radius > 0f) {
        floatArrayOf(
            0f,
            0f,
            0f,
            0f,
            radius,
            radius,
            radius,
            radius,
        )
    } else {
        null
    }
}

private fun TitleBar.applyTopBarChildConfig(
    config: TopBarConfig.Config,
    contentColor: Int = topBarContentColor(),
) {
    findViewById<TabLayout?>(R.id.tab_layout)?.applyTopBarChildConfig(config, contentColor)
    findViewById<View?>(R.id.search_view)?.applyTopBarChildConfig(config, contentColor)
}

private fun TitleBar.applyTransparentTopBarChildConfig() {
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    val contentColor = topBarContentColor()
    findViewById<TabLayout?>(R.id.tab_layout)?.apply {
        setBackgroundColor(Color.TRANSPARENT)
        setTabTextColors(tabTextColorStateList(contentColor))
        setSelectedTabIndicatorColor(
            resolveTabIndicatorColor(context, config, Color.TRANSPARENT),
        )
    }
    findViewById<View?>(R.id.search_view)?.applyTopBarChildConfig(config, contentColor)
}

private fun View.applyTopBarChildConfig(config: TopBarConfig.Config, contentColor: Int) {
    if (this !is TabLayout && id != R.id.search_view) return
    val tagBarColor = config.tagBarColor
        ?: ContextCompat.getColor(context, R.color.background_menu)
    if (this is TabLayout && id == R.id.tab_layout) {
        val tagBarAlpha = if (context.transparentNavBar) 0 else config.tagBarAlpha
        val barColor = TopBarConfig.withOpacity(tagBarColor, tagBarAlpha)
        setBackgroundColor(barColor)
        setTabTextColors(tabTextColorStateList(contentColor))
        setSelectedTabIndicatorColor(
            resolveTabIndicatorColor(context, config, barColor),
        )
    }
    if (id == R.id.search_view) {
        background = searchViewBackground()
        applyTint(contentColor)
        (this as? SearchView)?.setContentTint(contentColor)
    }
}

/**
 * 解析分组 TabLayout 的指示器（当前分组下划线）颜色。
 * 标签栏背景完全透明时（开启"顶栏颜色透明"或标签栏透明度为 0），
 * 指示器直接叠在页面背景上，而默认选中色是主题主色，常与页面背景同色而看不清，
 * 因此这种场景始终使用全局强调色；
 * 背景不透明时，若"选中标签颜色"与标签栏背景色同为 primaryColor（默认顶栏配置）
 * 会融为一体而不可见，此时同样回退为全局强调色，选中色透明度为 0 时也回退。
 */
private fun resolveTabIndicatorColor(
    context: Context,
    config: TopBarConfig.Config,
    barColor: Int,
): Int {
    if (Color.alpha(barColor) == 0) return context.accentColor
    val selectedColor = config.tagSelectedColor
        ?.let { TopBarConfig.withOpacity(it, config.tagSelectedAlpha) }
        ?: context.primaryColor
    val visible = TopBarConfig.opacityToAlpha(config.tagSelectedAlpha) > 0 &&
        selectedColor != barColor
    return if (visible) selectedColor else context.accentColor
}

private fun View.searchViewBackground(): Drawable {
    val radius = 35f * resources.displayMetrics.density
    val strokeWidth = (0.5f * resources.displayMetrics.density).coerceAtLeast(1f).toInt()
    val color = ContextCompat.getColor(context, R.color.transparent10)
    return GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        setStroke(strokeWidth, color)
    }
}

private fun tabTextColorStateList(contentColor: Int): ColorStateList {
    val normalColor = ColorUtils.setAlphaComponent(contentColor, 200)
    return ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_selected),
            StateSet.WILD_CARD,
        ),
        intArrayOf(contentColor, normalColor),
    )
}

private fun TitleBar.bitmapLayer(file: File, alphaPercent: Int, radius: Float): Drawable? {
    val bitmap = kotlin.runCatching {
        BitmapUtils.decodeBitmap(
            file.absolutePath,
            resources.displayMetrics.widthPixels.coerceAtLeast(1),
            height.takeIf { it > 0 } ?: (56 * resources.displayMetrics.density).toInt(),
        )
    }.getOrNull() ?: return null
    return TopBarWallpaperDrawable(
        bitmap = bitmap,
        radius = radius,
        alphaPercent = alphaPercent,
    )
}

/**
 * 顶栏壁纸 Drawable。
 * 使用 BitmapShader 平铺壁纸图片，支持圆角裁剪和透明度。
 * 图片以 center-crop 方式缩放填充。
 */
private class TopBarWallpaperDrawable(
    private val bitmap: Bitmap,
    private val radius: Float,
    alphaPercent: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        alpha = TopBarConfig.opacityToAlpha(alphaPercent)
    }
    private val rect = RectF()
    private val matrix = Matrix()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        rect.set(bounds)
        val scale = maxOf(
            bounds.width() / bitmap.width.toFloat(),
            bounds.height() / bitmap.height.toFloat(),
        )
        val dx = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
        val dy = bounds.top + (bounds.height() - bitmap.height * scale) / 2f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        paint.shader?.setLocalMatrix(matrix)
        path.reset()
        path.addRoundRect(
            rect,
            floatArrayOf(
                0f,
                0f,
                0f,
                0f,
                radius,
                radius,
                radius,
                radius,
            ),
            Path.Direction.CW,
        )
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android SDK")
    override fun getOpacity(): Int = if (paint.alpha >= 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1

    override fun getIntrinsicHeight(): Int = -1
}
