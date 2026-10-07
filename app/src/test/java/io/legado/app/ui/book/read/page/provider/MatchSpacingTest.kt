package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 命中字距的宽度测量单元测试。
 *
 * 这两个函数是「留白是真实排版宽度」的唯一口径：断行测量按字符取额外宽度，
 * 两端对齐/标题对齐按可视行取留白总和。算错就会表现为高亮文字右侧溢出或整行被压扁。
 *
 * 留白是否生效由 [CharStyle.matchStartsHere] / [CharStyle.matchEndsHere] 与折行位置决定，
 * 段内字符保留声明值但不自动让位（跨行时由行首/行尾判定接手，见最后一个用例）。
 */
class MatchSpacingTest {

    /** "abcd"：命中段为 "bc"，段首 b 带左侧留白 8，段尾 c 带右侧留白 6 */
    private val styles: Array<CharStyle?> = arrayOf(
        null,
        CharStyle(letterSpacingBefore = 8f, matchStartsHere = true),
        CharStyle(letterSpacingAfter = 6f, matchEndsHere = true),
        null,
    )

    @Test
    fun `断行额外宽度按字符给出命中段首尾留白`() {
        val widths = styles.matchSpacingWidths(4)
        assertEquals(0f, widths?.get(0))
        assertEquals(8f, widths?.get(1))
        assertEquals(6f, widths?.get(2))
        assertEquals(0f, widths?.get(3))
        assertEquals(4, widths?.size)
    }

    @Test
    fun `没有命中字距时不拷贝数组`() {
        assertNull(arrayOf<CharStyle?>(null, CharStyle(), null).matchSpacingWidths(3))
        assertNull((null as Array<CharStyle?>?).matchSpacingWidths(3))
    }

    @Test
    fun `可视行留白只统计落在行范围内的字符`() {
        assertEquals(14f, styles.measureLineMatchSpacing(0, 4))
        assertEquals(8f, styles.measureLineMatchSpacing(0, 2))
        assertEquals(6f, styles.measureLineMatchSpacing(2, 4))
        assertEquals(0f, styles.measureLineMatchSpacing(3, 4))
        assertEquals(0f, styles.measureLineMatchSpacing(3, 3))
    }

    @Test
    fun `单字命中时同一字符的左右留白都要算上`() {
        val single = arrayOf<CharStyle?>(
            CharStyle(
                letterSpacingBefore = 5f,
                letterSpacingAfter = 7f,
                matchStartsHere = true,
                matchEndsHere = true,
            ),
            null,
        )
        assertEquals(12f, single.matchSpacingWidths(2)?.get(0))
        assertEquals(12f, single.measureLineMatchSpacing(0, 1))
        assertEquals(12f, single.measureLineMatchSpacing(0, 2))
    }

    @Test
    fun `命中段折行后行首行尾同样让出留白`() {
        // "abcd" 整段命中并被折在第 2 个字符前：b、c 是段内字符，只有声明值
        val wrapped = arrayOf<CharStyle?>(
            null,
            CharStyle(letterSpacingBefore = 8f, matchStartsHere = true),
            CharStyle(letterSpacingBefore = 8f, letterSpacingAfter = 6f),
            CharStyle(letterSpacingAfter = 6f, matchEndsHere = true),
        )
        // 折行处：行首（b 之后）让出左侧留白，行尾（c）让出右侧留白
        assertEquals(8f, wrapped.lineSpacingBefore(2, true))
        assertEquals(6f, wrapped.lineSpacingAfter(2, true))
        // 不在行首/行尾、也不是段首/段尾时不生效
        assertEquals(0f, wrapped.lineSpacingBefore(2, false))
        assertEquals(0f, wrapped.lineSpacingAfter(2, false))
        // 跨行留白必须计入两端对齐基准，否则整行右侧溢出后会被反向压扁
        assertEquals(14f, wrapped.measureLineMatchSpacing(2, 4))
    }
}
