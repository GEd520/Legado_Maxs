package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ButtonColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读内容视图
 */
class ContentTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var selectAble = AppConfig.textSelectAble
    val selectedPaint by lazy {
        Paint().apply {
            color = context.getCompatColor(R.color.btn_bg_press_2)
            style = Paint.Style.FILL
        }
    }
    private var callBack: CallBack
    private val visibleRect = ChapterProvider.visibleRect
    val selectStart = TextPos(0, -1, -1)
    private val selectEnd = TextPos(0, -1, -1)
    var textPage: TextPage = TextPage()
        private set
    var isMainView = false
    var longScreenshot = false
    var reverseStartCursor = false
    var reverseEndCursor = false
    /** 排队中的跨页选择翻页方向（null 表示当前没有排队） */
    private var pendingSelectAutoPageForward: Boolean? = null
    /** 跨页选择排队时记下的手指位置与拖动端，翻页到点后继续用它更新选择 */
    private var lastSelectTouchX = 0f
    private var lastSelectTouchY = 0f
    private var lastSelectDragStartPoint = false
    /** 端点停在内容区边缘够久后执行的翻页 */
    private val selectAutoPageRunnable = Runnable {
        pendingSelectAutoPageForward = null
        selectAutoPage()
    }

    //滚动参数
    private val pageFactory get() = callBack.pageFactory
    private val pageDelegate get() = callBack.pageDelegate
    var pageOffset = 0
        private set
    private var autoPager: AutoPager? = null
    private var isScroll = false
    private val renderRunnable by lazy { Runnable { preRenderPage() } }
    private var lastClickTime = 0L
    private var doubleClick = false
    private val activeAnimatedColumns = Collections.newSetFromMap(IdentityHashMap<ImageColumn, Boolean>())
    private val drawingAnimatedColumns = Collections.newSetFromMap(IdentityHashMap<ImageColumn, Boolean>())

    //绘制图片的paint
    val imagePaint by lazy {
        Paint().apply {
            isAntiAlias = AppConfig.useAntiAlias
        }
    }

    init {
        callBack = activity as CallBack
    }

    /**
     * 设置内容
     */
    fun setContent(textPage: TextPage) {
        this.textPage = textPage
        // 非滑动翻页动画需要同步重绘，不然翻页可能会出现闪烁
        if (isScroll) {
            postInvalidate()
        } else {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isMainView) return
        ChapterProvider.upViewSize(w, h)
        textPage.format()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawingAnimatedColumns.clear()
        autoPager?.onDraw(canvas)
        if (longScreenshot) {
            canvas.translate(0f, scrollY.toFloat())
        }
        check(!visibleRect.isEmpty) { "visibleRect 为空" }
        canvas.clipRect(visibleRect)
        drawPage(canvas)
        syncAnimatedColumns()
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        var relativeOffset = relativeOffset(0)
        textPage.draw(this, canvas, relativeOffset)
        if (!callBack.isScroll) return
        //滚动翻页
        if (!pageFactory.hasNext()) return
        val textPage1 = relativePage(1)
        relativeOffset += textPage.height
        textPage1.draw(this, canvas, relativeOffset)
        if (!pageFactory.hasNextPlus()) return
        relativeOffset += textPage1.height
        if (relativeOffset < ChapterProvider.visibleHeight) {
            val textPage2 = relativePage(2)
            textPage2.draw(this, canvas, relativeOffset)
        }
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager?.computeOffset()
    }

    /**
     * 滚动事件
     * pageOffset 向上滚动 减小 向下滚动 增大
     * pageOffset 范围 0 ~ -textPage.height 大于0为上一页，小于-textPage.height为下一页
     * 以内容显示区域顶端为界，pageOffset的绝对值为textPage上方的高度
     * pageOffset + textPage.height 为 textPage 下方的高度
     */
    fun scroll(mOffset: Int) {
        pageOffset += mOffset
        if (longScreenshot) {
            scrollY += -mOffset
        }
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
            pageDelegate?.abortAnim()
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + textPage.height < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - textPage.height).toInt()
            pageOffset = min(0, offset)
            pageDelegate?.abortAnim()
        } else if (pageOffset > 0) {
            if (pageFactory.moveToPrev(true)) {
                pageOffset -= textPage.height.toInt()
                // 页窗口向后翻，跨页选择的选区随之平移
                shiftSelectPage(1)
            } else {
                pageOffset = 0
                pageDelegate?.abortAnim()
            }
        } else if (pageOffset < -textPage.height) {
            val height = textPage.height
            if (pageFactory.moveToNext(upContent = true)) {
                pageOffset += height.toInt()
                // 页窗口向前翻，跨页选择的选区随之平移
                shiftSelectPage(-1)
            } else {
                pageOffset = -height.toInt()
                pageDelegate?.abortAnim()
            }
        }
        postInvalidate()
    }

    fun submitRenderTask() {
        renderThread.submit(renderRunnable)
    }

    private fun preRenderPage() {
        val view = this
        var invalidate = false
        pageFactory.run {
            if (hasPrev() && prevPage.render(view)) {
                invalidate = true
            }
            if (curPage.render(view)) {
                invalidate = true
            }
            if (hasNext() && nextPage.render(view) && callBack.isScroll) {
                invalidate = true
            }
            if (hasNextPlus() && nextPlusPage.render(view) && callBack.isScroll
                && relativeOffset(2) < ChapterProvider.visibleHeight
            ) {
                invalidate = true
            }
            if (invalidate) {
                postInvalidate()
                pageDelegate?.postInvalidate()
            }
        }
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        pageOffset = 0
    }

    internal fun registerAnimatedColumn(column: ImageColumn) {
        drawingAnimatedColumns.add(column)
    }

    private fun syncAnimatedColumns() {
        val iterator = activeAnimatedColumns.iterator()
        while (iterator.hasNext()) {
            val column = iterator.next()
            if (!drawingAnimatedColumns.contains(column)) {
                column.detachAnimatedView()
                iterator.remove()
            }
        }
        drawingAnimatedColumns.forEach { column ->
            if (activeAnimatedColumns.add(column)) {
                column.attachAnimatedView(this)
            }
        }
    }

    override fun onDetachedFromWindow() {
        activeAnimatedColumns.forEach { it.detachAnimatedView() }
        activeAnimatedColumns.clear()
        drawingAnimatedColumns.clear()
        super.onDetachedFromWindow()
    }

    /**
     * 长按
     */
    fun longPress(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touch(x, y) { _, textPos, _, _, column ->
            when (column) {
                is ImageColumn -> callBack.onImageLongPress(x, y, column.src)
                is TextColumn -> {
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
                is TextHtmlColumn -> {
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
            }
        }
    }

    /**
     * 单击
     * @return true:已处理, false:未处理
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    fun click(x: Float, y: Float): Boolean {
        val currentTime = System.currentTimeMillis()
        val debounceClick = currentTime - lastClickTime < 300L //300毫秒防抖和双击
        lastClickTime = currentTime
        doubleClick = if (debounceClick) {
            !doubleClick
        } else {
            false
        }
        var handled = false
        touch(x, y) { _, textPos, textPage, textLine, column ->
            when (column) {
                is ButtonColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ReviewColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ImageColumn -> when (AppConfig.clickImgWay) {
                    "1" -> { //预览图片
                        activity?.showDialogFragment(PhotoDialog(column.src, isBook = true))
                        handled = true
                    }
                    "2" -> { //兼容处理
                        if (!debounceClick) {
                            if (ReadBook.book?.isOnLineTxt == true) {
                                val click = column.click
                                val src = column.src
                                if (!click.isNullOrBlank()) {
                                    callBack.clickImg(click, src)
                                    handled = true
                                } else {
                                    handled = callBack.oldClickImg(src)
                                }
                            }
                        }
                    }
                    "3" -> { //关闭
                        handled = false
                    }
                    "4" -> { //双击
                        if (doubleClick) {
                            val click = column.click
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        } else {
                            handled = true
                        }
                    }
                    else -> { //默认点击
                        if (!debounceClick) {
                            val click = column.click
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        }
                    }
                }
                is TextHtmlColumn -> {
                    column.linkUrl?.let {
                        activity?.startActivity<OpenUrlConfirmActivity> {
                            putExtra("uri", it)
                        }
                        handled = true
                    }
                }
            }
        }
        return handled
    }

    /**
     * 选择文字
     */
    fun selectText(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touchRough(x, y) { _, textPos, _, _, column ->
            if (column is TextBaseColumn) {
                column.selected = true
                select(textPos)
            }
        }
    }

    /**
     * 开始选择符移动
     */
    fun selectStartMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (selectStart.compare(textPos) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectEnd) <= 0) {
                selectStartMoveIndex(textPos)
            } else {
                touchRough(x - 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectEnd) > 0) {
                        reverseStartCursor = true
                        reverseEndCursor = false
                        selectEnd.columnIndex++
                        selectStartMoveIndex(selectEnd)
                        selectEndMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 结束选择符移动
     */
    fun selectEndMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (textPos.compare(selectEnd) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectStart) >= 0) {
                selectEndMoveIndex(textPos)
            } else {
                touchRough(x + 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectStart) < 0) {
                        reverseEndCursor = true
                        reverseStartCursor = false
                        selectStart.columnIndex--
                        selectEndMoveIndex(selectStart)
                        selectStartMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 选择端点被拖到内容区上下边缘外时排队翻页，翻页后在新页上继续选择（跨页选择）
     *
     * 端点停在边缘达到 [selectAutoPageDelay] 才翻页：手指中途移回内容区会取消排队，
     * 一直停在边缘则按同样节奏继续翻页。这样不会手一抖扫过边缘就一下翻好几页。
     * 各种翻页动画都支持：非滚动模式直接切到相邻页（不做动画，保证选区状态与页窗口同步切换），
     * 滚动模式滚动一页；只在同一章内翻页，避免选区的锚点落到取不到文字的页上。
     *
     * @param x 手指位置（内容视图坐标系）
     * @param y 手指位置（内容视图坐标系）
     * @param dragStartPoint 拖动的是选择起点还是终点
     */
    fun checkSelectAutoPage(x: Float, y: Float, dragStartPoint: Boolean) {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) {
            cancelSelectAutoPage()
            return
        }
        lastSelectTouchX = x
        lastSelectTouchY = y
        lastSelectDragStartPoint = dragStartPoint
        val forward = y >= ChapterProvider.visibleBottom
        val backward = !forward && y <= ChapterProvider.paddingTop
        // 手指没越过内容区上下边缘，取消排队中的翻页
        if (!forward && !backward) {
            cancelSelectAutoPage()
            return
        }
        // 已经按同一方向排队了，等它到点即可
        if (pendingSelectAutoPageForward == forward) return
        cancelSelectAutoPage()
        pendingSelectAutoPageForward = forward
        postDelayed(selectAutoPageRunnable, selectAutoPageDelay)
    }

    /**
     * 取消排队中的跨页选择翻页（手指移回内容区、抬起或取消选择时调用）
     */
    fun cancelSelectAutoPage() {
        if (pendingSelectAutoPageForward == null) return
        pendingSelectAutoPageForward = null
        removeCallbacks(selectAutoPageRunnable)
    }

    /**
     * 端点停在边缘够久了，翻一页并在新页上继续选择
     */
    private fun selectAutoPage() {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) return
        val y = lastSelectTouchY
        val forward = y >= ChapterProvider.visibleBottom
        if (!forward && y > ChapterProvider.paddingTop) return
        val relativePos = if (forward) 1 else -1
        val targetPage = relativePage(relativePos)
        // 只在同一章内翻页
        if (targetPage.textChapter !== textPage.textChapter || targetPage.lines.isEmpty()) {
            // 章节还没排版完说明只是目标页还在排版中，等下一轮再试；排完就没有下一页了
            if (!textPage.textChapter.isCompleted) {
                reArmSelectAutoPage(forward)
            }
            return
        }
        if (callBack.isScroll) {
            // 滚动模式滚动到相邻页，scroll() 内部同步选区位置
            val distance = if (forward) {
                -(textPage.height + pageOffset).toInt() - 1
            } else {
                -pageOffset + 1
            }
            scroll(distance)
        } else {
            val moved = if (forward) {
                callBack.pageFactory.moveToNext(true)
            } else {
                callBack.pageFactory.moveToPrev(true)
            }
            if (!moved) return
            // 非滚动模式页窗口直接位移，选区随之平移（滚动模式由 scroll() 平移）
            shiftSelectPage(-relativePos)
        }
        // 手指还在内容区外，端点落到新页的首/末行，手柄继续跟着手指
        val selectY = clampSelectY(y, forward)
        if (lastSelectDragStartPoint) {
            selectStartMove(lastSelectTouchX, selectY)
        } else {
            selectEndMove(lastSelectTouchX, selectY)
        }
        // 端点已经落到新页，通知界面把放大镜移到新的端点行
        callBack.onSelectAutoPageTurned(lastSelectDragStartPoint)
        // 手指仍停在边缘，按同样节奏继续翻页
        reArmSelectAutoPage(forward)
    }

    /**
     * 手指还停在边缘，按同样节奏再排一次跨页选择翻页
     */
    private fun reArmSelectAutoPage(forward: Boolean) {
        pendingSelectAutoPageForward = forward
        postDelayed(selectAutoPageRunnable, selectAutoPageDelay)
    }

    /**
     * 选择端点所在行的中线 y（本视图坐标）
     * 放大镜据此对准正在拖动的那一端文字，而不是手指落点，避免放大镜里看到的选中状态和实际不一致
     */
    fun getSelectEndpointLineCenterY(textPos: TextPos): Float {
        val page = relativePage(textPos.relativePagePos)
        val line = page.getLine(textPos.lineIndex)
        return relativeOffset(textPos.relativePagePos) + (line.lineTop + line.lineBottom) / 2f
    }

    /**
     * 页窗口整体位移后同步选区两端的位置，让锚点仍指向原来那段文字
     * @param offset 选区相对位置需要叠加的位移（翻到下一页为 -1，翻到上一页为 1）
     */
    private fun shiftSelectPage(offset: Int) {
        if (offset == 0) return
        if (selectStart.isSelected()) {
            selectStart.relativePagePos += offset
        }
        if (selectEnd.isSelected()) {
            selectEnd.relativePagePos += offset
        }
        callBack.onSelectPageShift(offset)
        upSelectChars()
    }

    /**
     * 手指落在内容区外时，把坐标夹回新页的首行/末行，保证端点能解析到文字
     */
    private fun clampSelectY(y: Float, forward: Boolean): Float {
        if (textPage.lineSize == 0) return y
        return if (forward) {
            val lastLineBottom = pageOffset + textPage.getLine(textPage.lineSize - 1).lineBottom
            min(y, min(lastLineBottom, ChapterProvider.visibleBottom.toFloat()) - 1f)
        } else {
            val firstLineTop = pageOffset + textPage.getLine(0).lineTop
            max(y, max(firstLineTop, ChapterProvider.paddingTop.toFloat()) + 1f)
        }
    }

    /**
     * 触碰位置信息
     * @param touched 回调
     */
    private fun touch(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        if (!visibleRect.contains(x, y)) return
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                if (textLine.isTouch(x, y, relativeOffset)) {
                    for ((charIndex, textColumn) in textLine.columns.withIndex()) {
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    return
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * 文本选择专用
     *
     * 手指落在行间空隙或内容区上下边缘之外时，就近吸附到 y 上方最近的一行，
     * 保证拖动选择端点时手柄始终跟着手指走（跨页选择顶到边缘也不会丢失端点）。
     * @param touched 回调
     */
    private fun touchRough(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        var relativeOffset: Float
        // y 上方最近的一行，未命中任何行时用它兜底
        var fallbackPos = -1
        var fallbackLineIndex = -1
        var fallbackOffset = 0f
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.getLine(lineIndex)
                if (textLine.isTouchY(y, relativeOffset)) {
                    touchRoughOnLine(
                        x, relativePos, relativeOffset, textPage, lineIndex, textLine, touched
                    )
                    return
                }
                if (textLine.lineTop + relativeOffset <= y) {
                    fallbackPos = relativePos
                    fallbackLineIndex = lineIndex
                    fallbackOffset = relativeOffset
                }
            }
        }
        if (fallbackPos < 0) return
        val fallbackPage = relativePage(fallbackPos)
        touchRoughOnLine(
            x,
            fallbackPos,
            fallbackOffset,
            fallbackPage,
            fallbackLineIndex,
            fallbackPage.getLine(fallbackLineIndex),
            touched
        )
    }

    /**
     * 解析行内触摸到的列并回调
     */
    private fun touchRoughOnLine(
        x: Float,
        relativePos: Int,
        relativeOffset: Float,
        textPage: TextPage,
        lineIndex: Int,
        textLine: TextLine,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        if (textPage.doublePage) {
            val halfWidth = width / 2
            if (textLine.isLeftLine && x > halfWidth) {
                return
            }
            if (!textLine.isLeftLine && x < halfWidth) {
                return
            }
        }
        val columns = textLine.columns
        if (columns.isEmpty()) return
        for (charIndex in columns.indices) {
            val textColumn = columns[charIndex]
            if (textColumn.isTouch(x)) {
                touched.invoke(
                    relativeOffset,
                    TextPos(relativePos, lineIndex, charIndex),
                    textPage, textLine, textColumn
                )
                return
            }
        }
        val isLast = columns.first().start < x
        val charIndex = if (isLast) columns.lastIndex + 1 else -1
        val textColumn = if (isLast) columns.last() else columns.first()
        touched.invoke(
            relativeOffset,
            TextPos(relativePos, lineIndex, charIndex),
            textPage, textLine, textColumn
        )
    }

    fun getCurVisiblePage(): TextPage {
        val visiblePage = TextPage()
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    visiblePage.addLine(visibleLine)
                }
            }
        }
        return visiblePage
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    return textPage.chapterIndex to visibleLine
                }
            }
        }
        return null
    }

    /**
     * 选择开始文字
     */
    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectStart.relativePagePos = relativePagePos
        selectStart.lineIndex = lineIndex
        selectStart.columnIndex = max(0, charIndex)
        val textLine = relativePage(relativePagePos).getLine(lineIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedStart(
            if (charIndex < textLine.columns.size) textColumn.start else textColumn.end,
            textLine.lineBottom + relativeOffset(relativePagePos),
            textLine.lineTop + relativeOffset(relativePagePos)
        )
        upSelectChars()
    }

    fun selectStartMoveIndex(textPos: TextPos) = textPos.run {
        selectStartMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    /**
     * 选择结束文字
     */
    fun selectEndMoveIndex(
        relativePage: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectEnd.relativePagePos = relativePage
        selectEnd.lineIndex = lineIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        selectEnd.columnIndex = min(charIndex, textLine.columns.lastIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedEnd(
            if (charIndex > -1) textColumn.end else textColumn.start,
            textLine.lineBottom + relativeOffset(relativePage),
            textLine.lineTop + relativeOffset(relativePage)
        )
        upSelectChars()
    }

    fun selectEndMoveIndex(textPos: TextPos) = textPos.run {
        selectEndMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    private fun upSelectChars() {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) {
            return
        }
        val last = if (callBack.isScroll) 2 else 0
        val textPos = TextPos(0, 0, 0)
        for (relativePos in 0..last) {
            textPos.relativePagePos = relativePos
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                textPos.lineIndex = lineIndex
                for ((charIndex, column) in textLine.columns.withIndex()) {
                    textPos.columnIndex = charIndex
                    if (column is TextBaseColumn) {
                        val compareStart = textPos.compare(selectStart)
                        val compareEnd = textPos.compare(selectEnd)
                        column.selected = compareStart >= 0 && compareEnd <= 0
                        column.isSearchResult =
                            column.selected && callBack.isSelectingSearchResult
                        column.isCurrentSearchResult = column.isSearchResult
                        if (column.isSearchResult) {
                            textPage.searchResult.add(column)
                        }
                    }
                }
            }
        }
        postInvalidate()
    }

    private fun upSelectedStart(x: Float, y: Float, top: Float) {
        callBack.run {
            upSelectedStart(x + imgBgPaddingStart, y + headerHeight, top + headerHeight)
        }
    }

    private fun upSelectedEnd(x: Float, y: Float, top: Float) {
        callBack.run {
            upSelectedEnd(x + imgBgPaddingStart, y + headerHeight, top + headerHeight)
        }
    }

    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        val windowEnd = if (callBack.isScroll) 2 else 0
        // 跨页选择时选区两端可能落在当前页窗口之外，按选区范围清理，避免旧页残留选中态
        val from = if (selectStart.isSelected()) min(selectStart.relativePagePos, 0) else 0
        val to = if (selectEnd.isSelected()) max(selectEnd.relativePagePos, windowEnd) else windowEnd
        for (relativePos in from..to) {
            val textPage = relativePage(relativePos)
            textPage.lines.forEach { textLine ->
                textLine.columns.forEach {
                    if (it is TextBaseColumn) {
                        it.selected = false
                        if (clearSearchResult) {
                            it.isSearchResult = false
                            it.isCurrentSearchResult = false
                            textPage.searchResult.remove(it)
                        }
                    }
                }
            }
        }
        selectStart.reset()
        selectEnd.reset()
        cancelSelectAutoPage()
        postInvalidate()
        callBack.onCancelSelect()
    }

    fun getSelectedText(): String {
        val textPos = TextPos(0, 0, 0)
        val builder = StringBuilder()
        for (relativePos in selectStart.relativePagePos..selectEnd.relativePagePos) {
            val textPage = relativePage(relativePos)
            textPos.relativePagePos = relativePos
            textPage.lines.forEachIndexed { lineIndex, textLine ->
                textPos.lineIndex = lineIndex
                textLine.columns.forEachIndexed { charIndex, column ->
                    textPos.columnIndex = charIndex
                    val compareStart = textPos.compare(selectStart)
                    val compareEnd = textPos.compare(selectEnd)
                    if (column is TextBaseColumn) {
                        when {
                            compareStart == -1 -> if (
                                selectStart.columnIndex == textLine.columns.size
                                && charIndex == textLine.columns.lastIndex
                            ) {
                                builder.append("\n")
                            }

                            compareEnd == 1 -> if (selectEnd.columnIndex == -1 && charIndex == 0) {
                                builder.append("\n")
                            }

                            compareStart >= 0 && compareEnd <= 0 -> {
                                builder.append(column.charData)
                                if (
                                    textLine.isParagraphEnd
                                    && charIndex == textLine.columns.lastIndex
                                    && compareEnd != 0
                                ) {
                                    builder.append("\n")
                                }
                            }
                        }
                    }
                }
            }
        }
        return builder.toString()
    }

    fun createBookmark(): Bookmark? {
        val page = relativePage(selectStart.relativePagePos)
        page.getTextChapter().let { chapter ->
            ReadBook.book?.let { book ->
                return book.createBookMark().apply {
                    chapterIndex = page.chapterIndex
                    chapterPos = chapter.getReadLength(page.index) +
                            page.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
                    chapterName = chapter.title
                    bookText = getSelectedText()
                }
            }
        }
        return null
    }

    /**
     * 相对当前页的绘制偏移
     * 支持章节内任意相对页（跨页选择时锚点会落在当前页窗口之外）
     */
    private fun relativeOffset(relativePos: Int): Float {
        return when {
            relativePos == 0 -> pageOffset.toFloat()
            relativePos > 0 -> {
                var offset = pageOffset.toFloat()
                for (pos in 0 until relativePos) {
                    offset += relativePage(pos).height
                }
                offset
            }

            else -> {
                var offset = pageOffset.toFloat()
                for (pos in -1 downTo relativePos) {
                    offset -= relativePage(pos).height
                }
                offset
            }
        }
    }

    /**
     * 获取相对当前页的页面
     * 0/1/2 走页工厂（含跨章兜底），更远的相对页直接取本章对应的页，
     * 取不到时给空页，避免跨页选择时越界崩溃
     */
    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            0 -> textPage
            1 -> pageFactory.nextPage
            2 -> pageFactory.nextPlusPage
            -1 -> pageFactory.prevPage
            else -> textPage.getTextChapter().getPage(textPage.index + relativePos) ?: emptyPage
        }
    }

    fun setAutoPager(autoPager: AutoPager?) {
        this.autoPager = autoPager
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return callBack.isScroll && pageFactory.hasNext()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longScreenshot = true
                scrollY = 0
            }

            MotionEvent.ACTION_UP -> {
                longScreenshot = false
                scrollY = 0
            }
        }
        return callBack.onLongScreenshotTouchEvent(event)
    }

    companion object {
        private val renderThread by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "TextPageRender")
            }
        }
        private val cursorWidth = 24.dpToPx()

        /** 跨页选择时端点停在内容区边缘多久后翻页（毫秒） */
        private const val selectAutoPageDelay = 800L

        /** 相对页越界时的只读占位空页 */
        private val emptyPage = TextPage()
    }

    interface CallBack {
        val headerHeight: Int
        val imgBgPaddingStart: Int
        val pageFactory: TextPageFactory
        val pageDelegate: PageDelegate?
        val isScroll: Boolean
        var isSelectingSearchResult: Boolean
        fun upSelectedStart(x: Float, y: Float, top: Float)
        fun upSelectedEnd(x: Float, y: Float, top: Float)
        fun onSelectPageShift(offset: Int)

        /**
         * 跨页选择自动翻页后端点落到新页
         * @param dragStartPoint 拖动的是选择起点还是终点
         */
        fun onSelectAutoPageTurned(dragStartPoint: Boolean)
        fun onImageLongPress(x: Float, y: Float, src: String)
        fun onCancelSelect()
        fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean
        fun oldClickImg(src: String): Boolean
        fun clickImg(click: String, src: String)
    }
}
