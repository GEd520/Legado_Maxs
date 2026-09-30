package io.legado.app.ui.book.read.page.provider

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.os.postDelayed
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.RealPathUtil

import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isPad
import io.legado.app.utils.postEvent
import io.legado.app.utils.spToPx
import io.legado.app.utils.textHeight
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx
import androidx.core.net.toUri
import kotlin.math.sqrt

/**
 * 解析内容生成章节和页面
 */
@Suppress("DEPRECATION", "ConstPropertyName")
object ChapterProvider {
    //用于图片字的替换
    const val srcReplaceStr = "袮" //▩▣ //这是不应该存在的汉字,会替换为祢，这个字符用来标记
    const val srcReplaceChar = '袮'
    const val srcReplacementChar = '祢'
    //用于评论按钮的替换
    const val reviewStr = "꧁"
    const val reviewChar = '꧁'
    const val indentChar = "　"

    @JvmStatic
    var viewWidth = 0
        private set

    @JvmStatic
    var viewHeight = 0
        private set

    @JvmStatic
    var paddingLeft = 0
        private set

    @JvmStatic
    var paddingTop = 0
        private set

    @JvmStatic
    var paddingRight = 0
        private set

    @JvmStatic
    var paddingBottom = 0
        private set

    @JvmStatic
    var visibleWidth = 0
        private set

    @JvmStatic
    var visibleHeight = 0
        private set

    @JvmStatic
    var visibleRight = 0
        private set

    @JvmStatic
    var visibleBottom = 0
        private set

    @JvmStatic
    var lineSpacingExtra = 0f
        private set

    @JvmStatic
    var paragraphSpacing = 0
        private set

    @JvmStatic
    var titleTopSpacing = 0
        private set

    @JvmStatic
    var titleBottomSpacing = 0
        private set

    @JvmStatic
    var indentCharWidth = 0f
        private set

    @JvmStatic
    var titlePaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    @JvmStatic
    var titlePaintFontMetrics = FontMetrics()

    @JvmStatic
    var contentPaintFontMetrics = FontMetrics()

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var contentPaint: TextPaint = TextPaint()

    /**
     * 标题/正文字重 < 400 时的变细擦除宽度（&gt;0 表示绘制阶段需用背景色描边擦掉字心边缘）。
     * 由 [getPaints] 按字号算出，供 TextLine/TextColumn 的绘制路径消费。
     */
    @JvmStatic
    var titleThinStrokeWidth: Float = 0f
        private set

    @JvmStatic
    var contentThinStrokeWidth: Float = 0f
        private set

    @JvmStatic
    var reviewPaint: TextPaint = TextPaint()

    @JvmStatic
    var doublePage = false
        private set

    @JvmStatic
    var visibleRect = RectF()

    init {
        upStyle()
    }

    fun getTextChapterAsync(
        scope: CoroutineScope,
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        chapterSize: Int,
    ): TextChapter {

        val textChapter = TextChapter(
            bookChapter,
            bookChapter.index, displayTitle,
            chapterSize,
            bookContent.sameTitleRemoved,
            bookChapter.isVip,
            bookChapter.isPay,
            bookContent.effectiveReplaceRules
        ).apply {
            createLayout(scope, book, bookContent)
        }

        return textChapter
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        getPaints(typeface).let {
            titlePaint = it.first
            contentPaint = it.second
//            reviewPaint.color = contentPaint.color
//            reviewPaint.textSize = contentPaint.textSize * 0.45f
//            reviewPaint.textAlign = Paint.Align.CENTER
        }
        //间距
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f
        paragraphSpacing = ReadBookConfig.paragraphSpacing
        titleTopSpacing = ReadBookConfig.titleTopSpacing.dpToPx()
        titleBottomSpacing = ReadBookConfig.titleBottomSpacing.dpToPx()
        val bodyIndent = ReadBookConfig.paragraphIndent
        indentCharWidth = if (bodyIndent.isNotEmpty()) {
            var indentWidth = StaticLayout.getDesiredWidth(bodyIndent, contentPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                indentWidth += contentPaint.letterSpacing * contentPaint.textSize
            }
            indentWidth / bodyIndent.length
        } else {
            0f
        }
        titlePaintTextHeight = titlePaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintFontMetrics = titlePaint.fontMetrics
        contentPaintFontMetrics = contentPaint.fontMetrics
        upLayout()
    }

    private fun getTypeface(fontPath: String): Typeface? {
        return kotlin.runCatching {
            when {
                fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver
                        .openFileDescriptor(fontPath.toUri(), "r")!!
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }

                fontPath.isContentScheme() -> {
                    Typeface.createFromFile(RealPathUtil.getPath(appCtx, fontPath.toUri()))
                }

                fontPath.isNotEmpty() -> Typeface.createFromFile(fontPath)
                else -> when (AppConfig.systemTypefaces) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
        }.getOrElse {
            ReadBookConfig.textFont = ""
            ReadBookConfig.save()
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    /**
     * 创建标题和正文的画笔
     *
     * 两种字重模式都归到一个"有效字重"上（[ReadBookConfig.getTitleBoldWeight] / [ReadBookConfig.getTextBoldWeight]），
     * 再分两条路落到画笔上：
     * - 系统字体：Android 9+ 由 `Typeface.create(weight)` 选最接近的静态字面；可变字体额外补 `wght` 变体轴
     * - 第三方字体：多是单字面静态字体，`Typeface.create(weight)` 只剩"是否合成粗体"两挡，
     *   于是 >400 用 strokeWidth 外扩连续加粗，<400 记录擦除宽度、绘制时用背景色描边减细
     *
     * @param typeface 基础字体
     * @return Pair<标题画笔, 正文画笔>
     */
    private fun getPaints(typeface: Typeface?): Pair<TextPaint, TextPaint> {
        val isCustomFont = ReadBookConfig.textFont.isNotEmpty()
        val isFineMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && AppConfig.textBoldMode == 1
        // 粗略模式系数更温和：三档间隔本来就大，且合成粗体会与描边叠加。
        // 加粗系数比参考实现更保守（0.10→0.07）：中文笔画密集，描边均一外扩会把字腔糊死，
        // 900 档实测糊成实心块，收到 0.07 后极粗档仍可辨认
        val thinCoefficient = if (isFineMode) 0.03f else 0.025f
        val boldCoefficient = if (isFineMode) 0.07f else 0.04f
        // 第三方字体的粗略模式统一回落到 NORMAL，三档差异全部交给描边/擦除，
        // 否则 Typeface.create 的合成粗体会和描边叠加，粗体档明显过粗
        val useWeightAxis = !(isCustomFont && !isFineMode)

        val titleWeight = ReadBookConfig.getTitleBoldWeight()
        val textWeight = ReadBookConfig.getTextBoldWeight()

        val titleFont = createWeightedTypeface(typeface, titleWeight, useWeightAxis)
        val textFont = createWeightedTypeface(typeface, textWeight, useWeightAxis)

        //标题
        val tPaint = TextPaint()
        tPaint.color = ReadBookConfig.textColor
        tPaint.letterSpacing = ReadBookConfig.letterSpacing
        tPaint.typeface = titleFont
        tPaint.textSize = with(ReadBookConfig) { textSize + titleSize }.toFloat().spToPx()
        tPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            tPaint.isLinearText = true
        }
        applyWeightAxis(tPaint, titleWeight, useWeightAxis)
        titleThinStrokeWidth = applyStrokeWeight(tPaint, titleWeight, isCustomFont, boldCoefficient, thinCoefficient)
        //正文
        val cPaint = TextPaint()
        cPaint.color = ReadBookConfig.textColor
        cPaint.letterSpacing = ReadBookConfig.letterSpacing
        cPaint.typeface = textFont
        cPaint.textSize = ReadBookConfig.textSize.toFloat().spToPx()
        cPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            cPaint.isLinearText = true
        }
        applyWeightAxis(cPaint, textWeight, useWeightAxis)
        contentThinStrokeWidth = applyStrokeWeight(cPaint, textWeight, isCustomFont, boldCoefficient, thinCoefficient)
        return Pair(tPaint, cPaint)
    }

    /**
     * 按字重取字体：Android 9+ 走三参数 weight，更低版本回退到两参数 style
     */
    private fun createWeightedTypeface(typeface: Typeface?, weight: Int, useWeightAxis: Boolean): Typeface {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Typeface.create(typeface, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
        return if (useWeightAxis) {
            Typeface.create(typeface, weight, false)
        } else {
            Typeface.create(typeface, Typeface.NORMAL)
        }
    }

    /**
     * 补 `wght` 变体轴：可变字体（含系统可变字体）由此拿到真正的连续字重，静态字面会忽略该设置
     */
    private fun applyWeightAxis(paint: TextPaint, weight: Int, useWeightAxis: Boolean) {
        if (useWeightAxis && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            paint.setFontVariationSettings("'wght' $weight")
        }
    }

    /**
     * 第三方字体变细：用背景色把刚画过的字再描一遍，擦掉字心边缘（字重 &lt; 400 时生效）。
     * 描边宽度为 0（未启用/非第三方字体）或取不到背景色（[ReadBookConfig.bgMeanColor] 为 0）时静默跳过。
     * 会临时改 paint 的 style/color/strokeWidth，返回前还原，因此可以安全作用于共享画笔。
     */
    @JvmStatic
    fun drawThinStroke(
        canvas: Canvas,
        paint: Paint,
        isTitle: Boolean,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float
    ) {
        val thinStrokeWidth = if (isTitle) titleThinStrokeWidth else contentThinStrokeWidth
        if (thinStrokeWidth <= 0f || ReadBookConfig.bgMeanColor == 0) return
        val oldStyle = paint.style
        val oldColor = paint.color
        val oldStrokeWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.color = ReadBookConfig.bgMeanColor
        paint.strokeWidth = thinStrokeWidth
        canvas.drawText(text, start, end, x, y, paint)
        paint.style = oldStyle
        paint.color = oldColor
        paint.strokeWidth = oldStrokeWidth
    }

    /**
     * 给画笔补上"变体轴之外"的字重差：>400 用描边外扩加粗，<400 返回擦除宽度（0f 表示不需擦除）
     *
     * @param weight 目标字重（100~900）
     * @param isCustomFont 是否第三方字体。系统字体有真实字面可用（见 [createWeightedTypeface]），
     *   再叠加描边会与字面本身重复，所以只有第三方字体走这条路
     */
    private fun applyStrokeWeight(
        paint: TextPaint,
        weight: Int,
        isCustomFont: Boolean,
        boldCoefficient: Float,
        thinCoefficient: Float
    ): Float {
        if (!isCustomFont || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0f
        if (weight > 400) {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = (weight - 400) / 500f * paint.textSize * boldCoefficient
            return 0f
        }
        if (weight == 400) return 0f
        // 变细靠"用背景色把字心边缘描掉"，平方根曲线让中间段变化更明显，
        // 上限压到字号的 2%，避免重=100 时擦除过度导致文字几乎消失
        val normalized = (400 - weight) / 300f
        return (sqrt(normalized) * paint.textSize * thinCoefficient)
            .coerceAtMost(paint.textSize * 0.02f)
    }

    /**
     * 更新View尺寸
     */
    fun upViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (width != viewWidth || height != viewHeight) {
            notifyViewSizeChange(width, height)
        }
    }

    private fun notifyViewSizeChange(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        upLayout()
        // 容器尺寸变更后，清除预加载的章节缓存
        // prevTextChapter/nextTextChapter 中的页面按旧尺寸排版，
        // 若不清除，后续 moveToNextChapter/moveToPrevChapter 会直接使用旧排版数据，
        // 导致内容渲染不全（横竖屏切换/小窗全屏切换场景复现）
        ReadBook.prevTextChapter = null
        ReadBook.nextTextChapter = null
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    /**
     * 更新绘制尺寸
     */
    fun upLayout() {
        when (AppConfig.doublePageHorizontal) {
            "0" -> doublePage = false
            "1" -> doublePage = true
            "2" -> {
                doublePage = (viewWidth > viewHeight)
                        && ReadBook.pageAnim() != 3
            }

            "3" -> {
                doublePage = (viewWidth > viewHeight || appCtx.isPad)
                        && ReadBook.pageAnim() != 3
            }
        }

        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        paddingLeft = ReadBookConfig.paddingLeft.dpToPx()
        paddingTop = ReadBookConfig.paddingTop.dpToPx()
        paddingRight = ReadBookConfig.paddingRight.dpToPx()
        paddingBottom = ReadBookConfig.paddingBottom.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        //留1dp画最后一行下划线
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight

        if (paddingLeft >= visibleRight || paddingTop >= visibleBottom) {
            AppLog.put("边距设置过大，请重新设置", toast = true)
            setFallbackLayout()
        }

        visibleRect.set( //留余，让溢出时也显示
            paddingLeft.toFloat() - 10,
            paddingTop.toFloat() - 10,
            visibleRight.toFloat() + 10,
            visibleBottom.toFloat() + 10f.dpToPx() //下划线最远10dp
        )

    }

    private fun setFallbackLayout() {
        paddingLeft = 20.dpToPx()
        paddingTop = 5.dpToPx()
        paddingRight = 20.dpToPx()
        paddingBottom = 5.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        //留1dp画最后一行下划线
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight
    }

}
