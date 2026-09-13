package io.legado.app.ui.book.read.page.entities

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.PaintPool
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextPage.Companion.emptyTextPage
import io.legado.app.ui.book.read.config.highlight.HighlightRule
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeededThenDraw
import io.legado.app.utils.dpToPx
import splitties.init.appCtx
import kotlin.math.roundToInt

/**
 * 行信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextLine(
    var text: String = "",
    private val textColumns: ArrayList<BaseColumn> = arrayListOf(),
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var indentWidth: Float = 0f,
    var paragraphNum: Int = 0,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    val isTitle: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isImage: Boolean = false,
    var isHtml: Boolean = false,
    var startX: Float = 0f,
    var indentSize: Int = 0,
    var extraLetterSpacing: Float = 0f,
    var extraLetterSpacingOffsetX: Float = 0f,
    var wordSpacing: Float = 0f,
    var exceed: Boolean = false,
    var onlyTextColumn: Boolean = true,
) {

    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: 0f
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: 0f
    val chapterIndices: IntRange get() = chapterPosition..chapterPosition + charSize
    val height: Float inline get() = lineBottom - lineTop
    val canvasRecorder = CanvasRecorderFactory.create()
    var searchResultColumnCount = 0
    var hasAnimatedColumn = false
    var isReadAloud: Boolean = false
        set(value) {
            if (field != value) {
                invalidate()
            }
            if (value) {
                textPage.hasReadAloudSpan = true
            }
            field = value
        }
    var textPage: TextPage = emptyTextPage
    var isLeftLine = true

    /**
     * 向行中添加文本列
     */
    fun addColumn(column: BaseColumn) {
        if (column !is TextColumn) {
            onlyTextColumn = false
        }
        if (column is ImageColumn && column.isAnimated) {
            hasAnimatedColumn = true
        }
        column.textLine = this
        textColumns.add(column)
    }

    /**
     * 向行中批量添加文本列
     */
    fun addColumns(columns: Collection<BaseColumn>) {
        onlyTextColumn = false
        columns.forEach { column ->
            if (column is ImageColumn && column.isAnimated) {
                hasAnimatedColumn = true
            }
            column.textLine = this
        }
        textColumns.addAll(columns)
    }

    /**
     * 获取指定位置的文本列，越界时返回最后一个
     * FIXED: 修复了当 textColumns 为空时调用 last() 导致的 NoSuchElementException
     * 同时修正了空列占位对象的构造函数参数，确保编译通过。
     */
    fun getColumn(index: Int): BaseColumn = textColumns.getOrElse(index) {
        textColumns.lastOrNull() ?: TextColumn(0f, 0f, "")
    }

    /**
     * 从后向前获取指定位置的文本列
     */
    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn = textColumns[textColumns.lastIndex - offset - index]

    /**
     * 获取行内文本列数量
     */
    fun getColumnsCount(): Int = textColumns.size

    /**
     * 更新行的顶部、底部和基线位置
     */
    fun upTopBottom(durY: Float, textHeight: Float, fontMetrics: Paint.FontMetrics) {
        lineTop = ChapterProvider.paddingTop + durY
        lineBottom = lineTop + textHeight
        lineBase = lineBottom - fontMetrics.descent
    }

    /**
     * 判断触摸坐标是否在当前行范围内
     */
    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean = y > lineTop + relativeOffset &&
        y < lineBottom + relativeOffset &&
        x >= lineStart &&
        x <= lineEnd + 20.dpToPx()

    /**
     * 判断触摸Y坐标是否在当前行范围内
     */
    fun isTouchY(y: Float, relativeOffset: Float): Boolean = y > lineTop + relativeOffset &&
        y < lineBottom + relativeOffset

    /**
     * 判断行是否在可视区域内
     */
    fun isVisible(relativeOffset: Float): Boolean {
        val top = lineTop + relativeOffset
        val bottom = lineBottom + relativeOffset
        val width = bottom - top
        val visibleTop = ChapterProvider.paddingTop
        val visibleBottom = ChapterProvider.visibleBottom
        val visible = when {
            // 完全可视
            top >= visibleTop && bottom <= visibleBottom -> true
            top <= visibleTop && bottom >= visibleBottom -> true
            // 上方第一行部分可视
            top < visibleTop && bottom > visibleTop && bottom < visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (bottom - visibleTop) / width
                    visibleRate > 0.6
                }
            }
            // 下方第一行部分可视
            top > visibleTop && top < visibleBottom && bottom > visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (visibleBottom - top) / width
                    visibleRate > 0.6
                }
            }
            // 不可视
            else -> false
        }
        return visible
    }

    /**
     * 绘制整行内容，包含优化渲染和普通渲染两种模式
     */
    fun draw(view: ContentTextView, canvas: Canvas) {
        if (AppConfig.optimizeRender && !hasAnimatedColumn) {
            canvasRecorder.recordIfNeededThenDraw(canvas, view.width, height.toInt()) {
                drawTextLine(view, this)
            }
        } else {
            drawTextLine(view, canvas)
        }
    }

    /**
     * 绘制行内文本和列内容，包含搜索高亮、墨水屏下划线、自定义下划线等
     */
    private fun drawTextLine(view: ContentTextView, canvas: Canvas) {
        drawCurrentSearchResultBackgrounds(canvas)
        drawStyledBackgrounds(canvas)
        if (checkFastDraw()) {
            fastDrawTextLine(view, canvas)
        } else {
            for (i in columns.indices) {
                columns[i].draw(view, canvas)
            }
        }

        // 墨水屏模式下的朗读和搜索下划线
        if (AppConfig.isEInkMode && (isReadAloud || searchResultColumnCount > 0)) {
            val underlinePaint = PaintPool.obtain()
            underlinePaint.set(ChapterProvider.contentPaint)
            underlinePaint.strokeWidth = einkUnderlineWidth
            val lineY = height - einkUnderlineWidth
            canvas.drawLine(lineStart + indentWidth, lineY, lineEnd, lineY, underlinePaint)
            PaintPool.recycle(underlinePaint)
        }

        drawStyledUnderlines(canvas)

        val underlineMode = ReadBookConfig.underlineMode
        if (underlineMode == 0) return
        if (!isImage && !isHtml && ReadBook.book?.isImage != true) {
            drawUnderline(canvas, underlineMode)
        }
    }

    /**
     * 快速绘制纯文本行，适用于优化渲染模式
     */
    @SuppressLint("NewApi")
    private fun fastDrawTextLine(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val textColor = if (isReadAloud) {
            ReadBookConfig.textAccentColor
        } else {
            ReadBookConfig.textColor
        }
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val paint = PaintPool.obtain()
        paint.set(textPaint)
        val letterSpacing = paint.letterSpacing * paint.textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        if (extraLetterSpacing != 0f) {
            paint.letterSpacing += extraLetterSpacing
        }
        if (wordSpacing != 0f) {
            paint.wordSpacing = wordSpacing
        }
        val offsetX = if (atLeastApi35) letterSpacingHalf else extraLetterSpacingOffsetX
        canvas.drawText(text, indentSize, text.length, startX + offsetX, lineBase - lineTop, paint)
        PaintPool.recycle(paint)
        for (i in columns.indices) {
            val column = columns[i] as TextColumn
            if (column.selected && !column.isSearchResult) {
                canvas.drawRect(column.start, 0f, column.end, height, view.selectedPaint)
            }
        }
    }

    /**
     * 绘制全局下划线（朗读标记除外），支持实线/虚线/波浪线/点线
     */
    private fun drawUnderline(canvas: Canvas, underlineMode: Int) {
        val underlineWidth = ReadBookConfig.durConfig.underlineWidth
        val underlineColor = ReadBookConfig.durConfig.curUnderlineColor()
        val paint = PaintPool.obtain()
        paint.set(ChapterProvider.contentPaint)
        paint.color = underlineColor
        paint.strokeWidth = underlineWidth.dpToPx().toFloat()
        paint.style = android.graphics.Paint.Style.STROKE
        paint.isAntiAlias = true
        val underlineOffset = ReadBookConfig.durConfig.underlineOffset
        val lineY = height + underlineOffset.dpToPx()
        val startX = lineStart + indentWidth
        val endX = lineEnd
        when (underlineMode) {
            1 -> canvas.drawLine(startX, lineY, endX, lineY, paint)
            2 -> drawDashedLine(canvas, paint, startX, lineY, endX, underlineWidth)
            3 -> drawWavyLine(canvas, paint, startX, lineY, endX, underlineWidth)
            4 -> drawDottedLine(canvas, paint, startX, lineY, endX, underlineWidth)
        }
        PaintPool.recycle(paint)
    }

    /**
     * 绘制虚线下划线，每段8dp线段+5dp间隔
     */
    private fun drawDashedLine(canvas: Canvas, paint: Paint, startX: Float, y: Float, endX: Float, underlineWidth: Float) {
        paint.strokeWidth = underlineWidth.dpToPx()
        val dashLen = 8.dpToPx().toFloat()
        val gapLen = 5.dpToPx().toFloat()
        var x = startX
        while (x < endX) {
            val x2 = (x + dashLen).coerceAtMost(endX)
            canvas.drawLine(x, y, x2, y, paint)
            x += dashLen + gapLen
        }
    }

    /**
     * 绘制点线下划线，2dp圆点+4dp间隔
     */
    private fun drawDottedLine(canvas: Canvas, paint: Paint, startX: Float, y: Float, endX: Float, underlineWidth: Float) {
        paint.strokeWidth = underlineWidth.dpToPx()
        val dotSize = 2.dpToPx().toFloat()
        val gapLen = 4.dpToPx().toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        var x = startX
        while (x < endX) {
            val x2 = (x + dotSize).coerceAtMost(endX)
            canvas.drawLine(x, y, x2, y, paint)
            x += dotSize + gapLen
        }
    }

    /**
     * 绘制波浪线下划线，使用贝塞尔曲线实现
     */
    private fun drawWavyLine(canvas: Canvas, paint: Paint, startX: Float, y: Float, endX: Float, underlineWidth: Float) {
        paint.strokeWidth = underlineWidth.dpToPx()
        val path = Path()
        val waveAmplitude = 3.dpToPx().toFloat()
        val waveLength = 12.dpToPx().toFloat()
        path.moveTo(startX, y)
        var currentX = startX
        while (currentX < endX) {
            val nextX = (currentX + waveLength).coerceAtMost(endX)
            val midX = (currentX + nextX) / 2
            path.quadTo(midX, y - waveAmplitude, nextX, y)
            currentX = nextX
            if (currentX < endX) {
                val nextX2 = (currentX + waveLength).coerceAtMost(endX)
                val midX2 = (currentX + nextX2) / 2
                path.quadTo(midX2, y + waveAmplitude, nextX2, y)
                currentX = nextX2
            }
        }
        canvas.drawPath(path, paint)
    }

    /**
     * 判断是否满足快速绘制条件
     */
    fun checkFastDraw(): Boolean {
        if (!AppConfig.optimizeRender || exceed || !onlyTextColumn || textPage.isMsgPage) {
            return false
        }
        if (wordSpacing != 0f && (!atLeastApi26 || !wordSpacingWorking)) {
            return false
        }
        if (searchResultColumnCount != 0) {
            return false
        }
        return columns.none {
            it is TextBaseColumn && (it.textColor != null || it.underlineMode != 0 || it.bgImage.isNotEmpty() || it.bgColor != null || it.fontPath.isNotEmpty())
        }
    }

    private fun drawStyledBackgrounds(canvas: Canvas) {
        if (isImage || columns.isEmpty()) return
        // 检查是否有背景颜色或背景图片
        if (columns.none { (it as? TextBaseColumn)?.let { c -> c.bgImage.isNotEmpty() || c.bgColor != null } == true }) return

        // 绘制背景颜色段
        drawBgColorSegments(canvas)

        // 绘制背景图片段
        drawBgImageSegments(canvas)
    }

    private fun drawBgColorSegments(canvas: Canvas) {
        var rangeStart = 0f
        var rangeEnd = 0f
        var currentBgColor: Int? = null
        var active = false
        columns.forEachIndexed { index, column ->
            val textColumn = column as? TextBaseColumn
            val bgColor = textColumn?.bgColor
            val bgImage = textColumn?.bgImage ?: ""
            // 只有在没有背景图片时才绘制背景颜色
            when {
                (bgColor == null || bgImage.isNotEmpty()) && active -> {
                    drawBgColorSegment(canvas, rangeStart, rangeEnd, currentBgColor!!)
                    active = false
                }
                bgColor != null && bgImage.isEmpty() && !active -> {
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    currentBgColor = bgColor
                    active = true
                }
                bgColor != null && bgImage.isEmpty() && bgColor == currentBgColor && active -> {
                    rangeEnd = textColumn!!.end
                }
                bgColor != null && bgImage.isEmpty() && active -> {
                    drawBgColorSegment(canvas, rangeStart, rangeEnd, currentBgColor!!)
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    currentBgColor = bgColor
                }
            }
            if (active && index == columns.lastIndex) {
                drawBgColorSegment(canvas, rangeStart, rangeEnd, currentBgColor!!)
            }
        }
    }

    private fun drawBgColorSegment(canvas: Canvas, startX: Float, endX: Float, bgColor: Int) {
        val paint = PaintPool.obtain()
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = bgColor
        val top = bgPaddingTop
        val bottom = height - bgPaddingBottom
        canvas.drawRect(startX, top, endX, bottom, paint)
        PaintPool.recycle(paint)
    }

    private fun drawBgImageSegments(canvas: Canvas) {
        var rangeStartIndex = 0
        var rangeEndIndex = 0
        var currentBgImage = ""
        var currentBgImageFit = 0
        var currentBgImageScale = 1f
        var currentNpLeft = 0.1f
        var currentNpTop = 0.1f
        var currentNpRight = 0.1f
        var currentNpBottom = 0.1f
        var currentBleedMode = HighlightRule.BLEED_SMART
        var currentSpacingH = 0f
        var currentSpacingV = 0f
        var active = false
        fun sameStyle(
            bgImage: String,
            bgImageFit: Int,
            bgImageScale: Float,
            npLeft: Float,
            npTop: Float,
            npRight: Float,
            npBottom: Float,
            bleedMode: Int,
            spacingH: Float,
            spacingV: Float,
        ) = bgImage == currentBgImage && bgImageFit == currentBgImageFit &&
            bgImageScale == currentBgImageScale && npLeft == currentNpLeft &&
            npTop == currentNpTop && npRight == currentNpRight && npBottom == currentNpBottom &&
            bleedMode == currentBleedMode && spacingH == currentSpacingH &&
            spacingV == currentSpacingV
        // 段的首尾列下标决定"邻字是谁"，智能策略要靠它判断能否借用邻接空白
        fun flush() = drawBgImageSegment(canvas, rangeStartIndex, rangeEndIndex)
        columns.forEachIndexed { index, column ->
            val textColumn = column as? TextBaseColumn
            val bgImage = textColumn?.bgImage ?: ""
            val bgImageFit = textColumn?.bgImageFit ?: 0
            val bgImageScale = textColumn?.bgImageScale ?: 1f
            val npLeft = textColumn?.npLeft ?: 0.1f
            val npTop = textColumn?.npTop ?: 0.1f
            val npRight = textColumn?.npRight ?: 0.1f
            val npBottom = textColumn?.npBottom ?: 0.1f
            val bleedMode = textColumn?.bgBleedMode ?: HighlightRule.BLEED_SMART
            val spacingH = textColumn?.bgSpacingH ?: 0f
            val spacingV = textColumn?.bgSpacingV ?: 0f
            when {
                bgImage.isEmpty() && active -> {
                    flush()
                    active = false
                }
                bgImage.isNotEmpty() && !active -> {
                    rangeStartIndex = index
                    rangeEndIndex = index
                    currentBgImage = bgImage
                    currentBgImageFit = bgImageFit
                    currentBgImageScale = bgImageScale
                    currentNpLeft = npLeft
                    currentNpTop = npTop
                    currentNpRight = npRight
                    currentNpBottom = npBottom
                    currentBleedMode = bleedMode
                    currentSpacingH = spacingH
                    currentSpacingV = spacingV
                    active = true
                }
                bgImage.isNotEmpty() && sameStyle(
                    bgImage, bgImageFit, bgImageScale, npLeft, npTop, npRight, npBottom,
                    bleedMode, spacingH, spacingV,
                ) -> {
                    rangeEndIndex = index
                }
                bgImage.isNotEmpty() -> {
                    flush()
                    rangeStartIndex = index
                    rangeEndIndex = index
                    currentBgImage = bgImage
                    currentBgImageFit = bgImageFit
                    currentBgImageScale = bgImageScale
                    currentNpLeft = npLeft
                    currentNpTop = npTop
                    currentNpRight = npRight
                    currentNpBottom = npBottom
                    currentBleedMode = bleedMode
                    currentSpacingH = spacingH
                    currentSpacingV = spacingV
                }
            }
            if (active && index == columns.lastIndex) {
                flush()
            }
        }
    }

    /**
     * 绘制高亮规则匹配文本的下划线段
     */
    private fun drawStyledUnderlines(canvas: Canvas) {
        if (isImage || columns.isEmpty()) return
        if (columns.none { (it as? TextBaseColumn)?.underlineMode?.let { m -> m != 0 } == true }) return
        var rangeStart = 0f
        var rangeEnd = 0f
        var mode = 0
        var color = 0
        var width = 1f
        var offset = 2f
        var svgPath = ""
        var active = false
        columns.forEachIndexed { index, column ->
            val textColumn = column as? TextBaseColumn
            val currentMode = textColumn?.underlineMode ?: 0
            val currentColor = textColumn?.underlineColor
                ?: textColumn?.textColor
                ?: ReadBookConfig.textColor
            val currentWidth = textColumn?.underlineWidth ?: 1f
            val currentOffset = textColumn?.underlineOffset ?: 2f
            val currentSvgPath = textColumn?.underlineSvgPath ?: ""
            val shouldContinue = active &&
                currentMode == mode &&
                currentColor == color &&
                currentWidth == width &&
                currentOffset == offset &&
                currentSvgPath == svgPath
            when {
                currentMode == 0 && active -> {
                    drawUnderlineSegment(canvas, rangeStart, rangeEnd, mode, color, width, offset, svgPath)
                    active = false
                }
                currentMode != 0 && !active -> {
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    mode = currentMode
                    color = currentColor
                    width = currentWidth
                    offset = currentOffset
                    svgPath = currentSvgPath
                    active = true
                }
                currentMode != 0 && shouldContinue -> {
                    rangeEnd = textColumn!!.end
                }
                currentMode != 0 -> {
                    drawUnderlineSegment(canvas, rangeStart, rangeEnd, mode, color, width, offset, svgPath)
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    mode = currentMode
                    color = currentColor
                    width = currentWidth
                    offset = currentOffset
                    svgPath = currentSvgPath
                }
            }
            if (active && index == columns.lastIndex) {
                drawUnderlineSegment(canvas, rangeStart, rangeEnd, mode, color, width, offset, svgPath)
            }
        }
    }

    /**
     * 绘制当前搜索结果匹配区域的高亮背景
     */
    private fun drawCurrentSearchResultBackgrounds(canvas: Canvas) {
        if (columns.isEmpty() || searchResultColumnCount == 0) return
        var startX = 0f
        var endX = 0f
        var active = false
        columns.forEachIndexed { index, column ->
            val textColumn = column as? TextBaseColumn
            val current = textColumn?.isCurrentSearchResult == true
            when {
                current && !active -> {
                    startX = textColumn.start
                    endX = textColumn.end
                    active = true
                }
                current -> {
                    endX = textColumn.end
                }
                active -> {
                    drawCurrentSearchRange(canvas, startX, endX)
                    active = false
                }
            }
            if (active && index == columns.lastIndex) {
                drawCurrentSearchRange(canvas, startX, endX)
            }
        }
    }

    /**
     * 绘制搜索结果匹配范围的圆角背景
     */
    private fun drawCurrentSearchRange(canvas: Canvas, startX: Float, endX: Float) {
        val paint = PaintPool.obtain()
        paint.set(ChapterProvider.contentPaint)
        paint.color = (0x33 shl 24) or (ReadBookConfig.textAccentColor and 0x00FFFFFF)
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawRoundRect(
            startX,
            searchPadding,
            endX,
            height - searchPadding,
            searchRadius,
            searchRadius,
            paint,
        )
        PaintPool.recycle(paint)
    }

    /**
     * 绘制单段下划线，用于高亮规则匹配区域，支持实线/虚线/波浪线/双下划线/自定义SVG/删除线/方框
     */
    private fun drawUnderlineSegment(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        underlineMode: Int,
        underlineColor: Int,
        underlineWidth: Float = 1f,
        underlineOffset: Float = 2f,
        svgPathStr: String = "",
    ) {
        val paint = PaintPool.obtain()
        paint.set(ChapterProvider.contentPaint)
        paint.color = underlineColor
        paint.strokeWidth = underlineWidth.dpToPx()
        paint.style = android.graphics.Paint.Style.STROKE
        paint.isAntiAlias = true
        val lineY = height + underlineOffset.dpToPx()
        when (underlineMode) {
            1 -> canvas.drawLine(startX, lineY, endX, lineY, paint)
            2 -> drawDashedLine(canvas, paint, startX, lineY, endX, underlineWidth)
            3 -> drawWavyLine(canvas, paint, startX, lineY, endX, underlineWidth)
            4 -> {
                val line2Y = lineY + doubleLineGap + underlineWidth.dpToPx()
                canvas.drawLine(startX, lineY, endX, lineY, paint)
                canvas.drawLine(startX, line2Y, endX, line2Y, paint)
            }
            5 -> {
                if (svgPathStr.isNotBlank()) {
                    drawSvgPath(canvas, startX, endX, lineY, svgPathStr, paint)
                }
            }
            6 -> {
                // 删除线：在文字垂直中线处绘制
                val fm = paint.fontMetrics
                val baselineY = height - fm.descent
                val centerY = baselineY + (fm.ascent + fm.descent) / 2f
                canvas.drawLine(startX, centerY, endX, centerY, paint)
            }
            8 -> {
                // 方框：紧贴文字绘制矩形，整行匹配时自动变为长方形
                val fm = paint.fontMetrics
                val baselineY = height - fm.descent
                val pad = 1.dpToPx().toFloat()
                val boxTop = baselineY + fm.ascent - pad
                val boxBottom = baselineY + fm.descent + pad
                canvas.drawRect(startX, boxTop, endX, boxBottom, paint)
            }
        }
        PaintPool.recycle(paint)
    }

    private fun drawSvgPath(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        lineY: Float,
        svgPathStr: String,
        paint: Paint,
    ) {
        val baseWidth = 100f
        val baseY = 50f
        val path = io.legado.app.ui.book.read.config.SvgPathParser.parse(svgPathStr) ?: return

        val width = endX - startX
        val scaleX = width / baseWidth
        val scaleY = 1f
        val translateX = startX
        val translateY = lineY - baseY

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleX, scaleY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /**
     * 当前行正文/标题字号，用于把背景图间距（em）换算成像素。
     */
    private val styleTextSize: Float
        get() = if (isTitle) ChapterProvider.titlePaint.textSize else ChapterProvider.contentPaint.textSize

    /**
     * 当前行正文/标题的字体度量：九宫格上下范围以文字上下界为基准，不能用行盒内缩——
     * 文字基线贴着行盒底部，按行盒内缩会把字身上下各切掉一段（表现为背景包不住文字）。
     */
    private val styleFontMetrics: Paint.FontMetrics
        get() = if (isTitle) ChapterProvider.titlePaint.fontMetrics else ChapterProvider.contentPaint.fontMetrics

    /**
     * 邻接空白字符的宽度：邻字是无字形的空白（空格/制表符等）时返回其推进宽度，否则返回 0。
     * 智能策略只借用这份空白向外扩，邻字有字形时绝不外扩。
     */
    private fun blankNeighborWidth(index: Int): Float {
        val neighbor = columns.getOrNull(index) as? TextBaseColumn ?: return 0f
        val char = neighbor.charData
        if (char.isEmpty() || !char.isBlank()) return 0f
        return neighbor.end - neighbor.start
    }

    /**
     * 行与行之间空白（行距）的一半：智能策略用它把背景上下撑开，正好填满行距段、
     * 与相邻行的背景相接，又不会压到上下行的字形。
     */
    private fun halfLineGap(): Float {
        val lineGap = height * (ChapterProvider.lineSpacingExtra - 1f)
        return if (lineGap > 0f) lineGap / 2f else 0f
    }

    /**
     * 绘制一段连续的背景图。[startIndex]..[endIndex] 为同一套样式的连续列，
     * 两端之外的第一列就是"邻字"，智能策略据此判断能否借用空白。
     */
    private fun drawBgImageSegment(canvas: Canvas, startIndex: Int, endIndex: Int) {
        val first = columns.getOrNull(startIndex) as? TextBaseColumn ?: return
        val last = columns.getOrNull(endIndex) as? TextBaseColumn ?: return
        val bitmap = getBgBitmap(first.bgImage) ?: return
        val startX = first.start
        val endX = last.end
        if (first.bgImageFit == 3) {
            // 九宫格：按用户调整的分割比例与外扩策略切图拉伸；上下以文字上下界为基准
            val textSize = styleTextSize
            val baseline = lineBase - lineTop
            val fontMetrics = styleFontMetrics
            drawNineSlice(
                bitmap, canvas, startX, baseline + fontMetrics.ascent, endX,
                baseline + fontMetrics.descent,
                first.npLeft, first.npTop, first.npRight, first.npBottom,
                first.bgBleedMode,
                leftBlankWidth = blankNeighborWidth(startIndex - 1),
                rightBlankWidth = blankNeighborWidth(endIndex + 1),
                verticalBlankSpace = halfLineGap(),
                spacingH = first.bgSpacingH * textSize,
                spacingV = first.bgSpacingV * textSize,
            )
            return
        }
        // 其余适配方式保持原有绘制范围
        val top = bgPaddingTop
        val bottom = height - bgPaddingBottom
        val paint = PaintPool.obtain()
        paint.style = android.graphics.Paint.Style.FILL
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        val rectWidth = endX - startX
        val rectHeight = bottom - top
        val scale = first.bgImageScale.coerceIn(0.1f, 5f)
        when (first.bgImageFit) {
            1 -> {
                val sw = rectWidth * scale
                val sh = rectHeight * scale
                val dx = startX + (rectWidth - sw) / 2f
                val dy = top + (rectHeight - sh) / 2f
                canvas.save()
                canvas.clipRect(startX, top, endX, bottom)
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(dx, dy, dx + sw, dy + sh), paint)
                canvas.restore()
            }
            2 -> {
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                val fitScale = (rectWidth / bw).coerceAtLeast(rectHeight / bh) * scale
                val scaledW = bw * fitScale
                val scaledH = bh * fitScale
                val dx = startX + (rectWidth - scaledW) / 2f
                val dy = top + (rectHeight - scaledH) / 2f
                canvas.save()
                canvas.clipRect(startX, top, endX, bottom)
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(dx, dy, dx + scaledW, dy + scaledH), paint)
                canvas.restore()
            }
            else -> {
                val tileBitmap = if (scale != 1f) {
                    val sw = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val sh = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    getScaledBitmap("${first.bgImage}_s$scale", bitmap, sw, sh)
                } else {
                    bitmap
                }
                val shader = BitmapShader(tileBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val matrix = android.graphics.Matrix()
                matrix.setTranslate(startX, top)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                canvas.drawRect(startX, top, endX, bottom, paint)
                paint.shader = null
            }
        }
        PaintPool.recycle(paint)
    }

    /**
     * 触发行重绘，同时刷新页面缓存
     */
    fun invalidate() {
        invalidateSelf()
        textPage.invalidate()
    }

    /**
     * 仅触发行自身缓存失效
     */
    fun invalidateSelf() {
        canvasRecorder.invalidate()
    }

    /**
     * 释放 Canvas 录制器资源
     */
    fun recycleRecorder() {
        canvasRecorder.recycle()
    }

    /**
     * 静态常量和兼容性检测
     */
    @SuppressLint("NewApi")
    companion object {
        val emptyTextLine = TextLine()
        private val atLeastApi26 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val atLeastApi28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        private val atLeastApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        private val bgPaddingTop = 1.dpToPx().toFloat()
        private val bgPaddingBottom = 1.dpToPx().toFloat()
        private val waveAmplitude = 3.dpToPx().toFloat()
        private val waveLength = 12.dpToPx().toFloat()
        private val doubleLineGap = 3.dpToPx().toFloat()
        private val searchRadius = 5.dpToPx().toFloat()
        private val searchPadding = 1.dpToPx().toFloat()
        private val einkUnderlineWidth = 1.dpToPx().toFloat()
        private val bgBitmapCache = android.util.LruCache<String, Bitmap>(16 * 1024 * 1024)
        private val bgScaledBitmapCache = android.util.LruCache<String, Bitmap>(8 * 1024 * 1024)

        /** 智能策略借用邻接空白时只取其中的一部分，给后面那个字留出余量 */
        private const val SMART_BLANK_RATIO = 0.8f
        private val bgSampleWidth by lazy {
            appCtx.resources.displayMetrics.widthPixels
        }
        private val bgSampleHeight by lazy {
            appCtx.resources.displayMetrics.heightPixels
        }

        fun getBgBitmap(path: String): Bitmap? {
            if (path.isBlank()) return null
            bgBitmapCache.get(path)?.let { return it }
            val bitmap = loadBgBitmap(path) ?: return null
            bgBitmapCache.put(path, bitmap)
            return bitmap
        }

        /**
         * 手动九宫格绘制：按 [npLeft]/[npTop]/[npRight]/[npBottom]（占位图宽高比例，0-1，
         * 左右相加、上下相加不超过 1）把位图切成 3×3，四个角保持原始尺寸，四条边与中心分别拉伸。
         *
         * 四角一律画在目标矩形内部（对齐 .9.png 语义），目标矩形 = 匹配区 + 自动外扩 + 手动间距：
         * - [bleedMode] 决定**自动外扩**量：[HighlightRule.BLEED_STRICT] 不外扩；
         *   [HighlightRule.BLEED_SMART] 只占用邻接空白（水平借 [leftBlankWidth]/[rightBlankWidth]，
         *   垂直借 [verticalBlankSpace]，即一半行距）；[HighlightRule.BLEED_FORCE] 按四角厚度外扩，
         *   即原来的"向外包裹文字"行为，可能压到相邻未匹配文字。
         * - [spacingH]/[spacingV] 为手动微调（调用方已换算成像素）：正数把背景向外撑大、离文字更远，
         *   负数向内收；与自动外扩叠加，所以"严格 + 正间距"也仍是用户主动往外撑。
         * 目标矩形放不下两侧边框时按比例收缩，避免短匹配 / 大间距时绘制区域失控。
         */
        fun drawNineSlice(
            bitmap: Bitmap,
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            npLeft: Float,
            npTop: Float,
            npRight: Float,
            npBottom: Float,
            bleedMode: Int,
            leftBlankWidth: Float,
            rightBlankWidth: Float,
            verticalBlankSpace: Float,
            spacingH: Float,
            spacingV: Float,
        ) {
            val bw = bitmap.width
            val bh = bitmap.height
            if (bw <= 0 || bh <= 0) return
            // 切割点先按 0-1 换算，交叉（比例之和超 1 的异常数据）时压回保证三段单调
            val rightCutX = bw - (bw * npRight.coerceIn(0f, 1f)).roundToInt()
            val leftCutX = (bw * npLeft.coerceIn(0f, 1f)).roundToInt().coerceAtMost(rightCutX)
            val rightCutY = bh - (bh * npBottom.coerceIn(0f, 1f)).roundToInt()
            val leftCutY = (bh * npTop.coerceIn(0f, 1f)).roundToInt().coerceAtMost(rightCutY)
            val srcX = intArrayOf(0, leftCutX, rightCutX, bw)
            val srcY = intArrayOf(0, leftCutY, rightCutY, bh)
            var leftW = srcX[1].toFloat()
            var rightW = (bw - srcX[2]).toFloat()
            var topH = srcY[1].toFloat()
            var bottomH = (bh - srcY[2]).toFloat()
            // 自动外扩量：按策略决定。强制模式用四角厚度，与原来的"向外包裹"完全等价
            val bleedLeft: Float
            val bleedRight: Float
            val bleedTop: Float
            val bleedBottom: Float
            when (bleedMode) {
                HighlightRule.BLEED_FORCE -> {
                    bleedLeft = leftW
                    bleedRight = rightW
                    bleedTop = topH
                    bleedBottom = bottomH
                }
                HighlightRule.BLEED_STRICT -> {
                    bleedLeft = 0f
                    bleedRight = 0f
                    bleedTop = 0f
                    bleedBottom = 0f
                }
                else -> {
                    bleedLeft = leftBlankWidth * SMART_BLANK_RATIO
                    bleedRight = rightBlankWidth * SMART_BLANK_RATIO
                    bleedTop = verticalBlankSpace
                    bleedBottom = verticalBlankSpace
                }
            }
            // 目标矩形 = 匹配区 + 自动外扩 + 手动间距（正数向外撑、负数向内收）
            val frameLeft = left - bleedLeft - spacingH
            val frameRight = right + bleedRight + spacingH
            val frameTop = top - bleedTop - spacingV
            val frameBottom = bottom + bleedBottom + spacingV
            val frameW = frameRight - frameLeft
            val frameH = frameBottom - frameTop
            if (frameW <= 0f || frameH <= 0f) return
            val horizontalFixed = leftW + rightW
            if (horizontalFixed > frameW && horizontalFixed > 0f) {
                val ratio = frameW / horizontalFixed
                leftW *= ratio
                rightW *= ratio
            }
            val verticalFixed = topH + bottomH
            if (verticalFixed > frameH && verticalFixed > 0f) {
                val ratio = frameH / verticalFixed
                topH *= ratio
                bottomH *= ratio
            }
            val dstX = floatArrayOf(frameLeft, frameLeft + leftW, frameRight - rightW, frameRight)
            val dstY = floatArrayOf(frameTop, frameTop + topH, frameBottom - bottomH, frameBottom)
            val paint = PaintPool.obtain()
            paint.style = android.graphics.Paint.Style.FILL
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            for (row in 0..2) {
                for (col in 0..2) {
                    if (srcX[col] == srcX[col + 1] ||
                        srcY[row] == srcY[row + 1] ||
                        dstX[col] == dstX[col + 1] ||
                        dstY[row] == dstY[row + 1]
                    ) {
                        continue
                    }
                    canvas.drawBitmap(
                        bitmap,
                        android.graphics.Rect(srcX[col], srcY[row], srcX[col + 1], srcY[row + 1]),
                        android.graphics.RectF(dstX[col], dstY[row], dstX[col + 1], dstY[row + 1]),
                        paint,
                    )
                }
            }
            PaintPool.recycle(paint)
        }

        private fun openBgImageStream(path: String): java.io.InputStream? = try {
            when {
                path.startsWith("assets://") -> appCtx.assets.open(path.removePrefix("assets://"))
                path.startsWith("content://") ->
                    appCtx.contentResolver.openInputStream(android.net.Uri.parse(path))
                else -> {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        file.inputStream()
                    } else {
                        val assetPath = if (path.startsWith("bg/")) path else "bg/$path"
                        kotlin.runCatching { appCtx.assets.open(assetPath) }.getOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            null
        }

        private fun getScaledBitmap(path: String, source: Bitmap, width: Int, height: Int): Bitmap {
            if (width <= 0 || height <= 0) return source
            val key = "${path}_${width}_$height"
            bgScaledBitmapCache.get(key)?.let { return it }
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            bgScaledBitmapCache.put(key, scaled)
            return scaled
        }

        private fun loadBgBitmap(path: String): Bitmap? = try {
            val ctx = appCtx
            if (path.startsWith("assets://")) {
                val assetPath = path.removePrefix("assets://")
                ctx.assets.open(assetPath).use { input ->
                    decodeSampledBitmap(input)
                }
            } else if (path.startsWith("content://")) {
                val uri = android.net.Uri.parse(path)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    decodeSampledBitmap(input)
                }
            } else {
                val file = java.io.File(path)
                if (file.exists()) {
                    decodeSampledBitmapFile(path)
                } else {
                    val assetPath = if (path.startsWith("bg/")) path else "bg/$path"
                    kotlin.runCatching {
                        ctx.assets.open(assetPath).use { input ->
                            decodeSampledBitmap(input)
                        }
                    }.getOrNull()
                }
            }
        } catch (e: Exception) {
            null
        }

        private fun decodeSampledBitmap(input: java.io.InputStream): Bitmap? {
            val buffered = if (input.markSupported()) input else java.io.BufferedInputStream(input)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            buffered.mark(buffered.available())
            BitmapFactory.decodeStream(buffered, null, options)
            options.inSampleSize = calculateInSampleSize(options, bgSampleWidth, bgSampleHeight)
            options.inJustDecodeBounds = false
            buffered.reset()
            return BitmapFactory.decodeStream(buffered, null, options)
        }

        private fun decodeSampledBitmapFile(path: String): Bitmap? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = calculateInSampleSize(options, bgSampleWidth, bgSampleHeight)
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeFile(path, options)
        }

        private fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int,
        ): Int {
            val (height, width) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        fun clearBgBitmapCache() {
            bgBitmapCache.evictAll()
            bgScaledBitmapCache.evictAll()
        }

        fun copyBgImageToInternal(context: android.content.Context, sourcePath: String): String? {
            if (sourcePath.startsWith("assets://")) return sourcePath
            return try {
                val dir = java.io.File(context.filesDir, "bg_images")
                if (!dir.exists()) dir.mkdirs()
                val hash = Integer.toHexString(sourcePath.hashCode()).replace("-", "n")
                val ext = sourcePath.substringAfterLast('.', "png")
                val fileName = "bg_$hash.$ext"
                val destFile = java.io.File(dir, fileName)
                if (destFile.exists()) return destFile.absolutePath
                if (sourcePath.startsWith("content://")) {
                    val uri = android.net.Uri.parse(sourcePath)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    val srcFile = java.io.File(sourcePath)
                    if (srcFile.exists()) {
                        srcFile.inputStream().use { input ->
                            java.io.FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } else {
                        return sourcePath
                    }
                }
                destFile.absolutePath
            } catch (e: Exception) {
                sourcePath
            }
        }

        fun cleanupUnusedBgImages(context: android.content.Context, usedPaths: Set<String>) {
            val dir = java.io.File(context.filesDir, "bg_images")
            if (!dir.exists()) return
            dir.listFiles()?.forEach { file ->
                if (file.absolutePath !in usedPaths) {
                    bgBitmapCache.remove(file.absolutePath)
                    file.delete()
                }
            }
        }
        private val wordSpacingWorking by lazy {
            // issue 3785 3846
            val paint = PaintPool.obtain()
            val text = "一二 三"
            val width1 = paint.measureText(text)
            try {
                paint.wordSpacing = 10f
                val width2 = paint.measureText(text)
                width2 - width1 == 10f
            } catch (e: NoSuchMethodError) {
                false
            } finally {
                PaintPool.recycle(paint)
            }
        }
    }
}
