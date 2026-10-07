package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * 选区放大镜浮层
 *
 * 放在阅读布局最上层（盖住选择手柄），真正的绘制交给 [ReadView]。
 * 放大镜采用"自绘正文"的方式：直接把页面内容按比例画进气泡，而不是用系统 Magnifier
 * 抓一张屏幕快照——快照是异步的、拿到的可能是上一帧，拖动时会看到气泡里的选中状态和
 * 实际选区不一致。自绘后在气泡里看到的永远是当前帧的真实选中状态。
 */
class SelectionMagnifierView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var readView: ReadView? = null

    init {
        // 纯 View 默认跳过 onDraw，必须显式关掉；另外不能抢触摸事件（不消费即透传给下层）
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        readView?.drawSelectionMagnifier(canvas)
    }
}
