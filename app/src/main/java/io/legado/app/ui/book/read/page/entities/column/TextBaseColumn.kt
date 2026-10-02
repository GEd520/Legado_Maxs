package io.legado.app.ui.book.read.page.entities.column

import io.legado.app.help.config.ReadBookConfig

/**
 * 文字基列
 */
interface TextBaseColumn : BaseColumn {
    override var start: Float
    override var end: Float
    val charData: String
    val textColor: Int?
    val underlineMode: Int
    val underlineColor: Int?
    val underlineWidth: Float
    val underlineOffset: Float
    val underlineSvgPath: String
    val bgColor: Int?
    val bgImage: String
    val bgImageFit: Int
    val bgImageScale: Float

    /** 九宫格分割比例（0-1，左右相加、上下相加不超过 1），bgImageFit=3 时生效 */
    val npLeft: Float get() = 0.1f
    val npTop: Float get() = 0.1f
    val npRight: Float get() = 0.1f
    val npBottom: Float get() = 0.1f

    /** 九宫格外扩策略，默认值与 HighlightRule.BLEED_SMART 保持一致 */
    val bgBleedMode: Int get() = 1

    /** 背景图左间距（em），与 HighlightRule.bgSpacingLeft 口径一致 */
    val bgSpacingLeft: Float get() = 0f

    /** 背景图右间距（em），与 HighlightRule.bgSpacingRight 口径一致 */
    val bgSpacingRight: Float get() = 0f

    /** 背景图上间距（em），与 HighlightRule.bgSpacingTop 口径一致 */
    val bgSpacingTop: Float get() = 0f

    /** 背景图下间距（em），与 HighlightRule.bgSpacingBottom 口径一致 */
    val bgSpacingBottom: Float get() = 0f

    /** 高亮规则指定字体路径，空串表示跟随阅读字体 */
    val fontPath: String get() = ""
    var selected: Boolean
    var isSearchResult: Boolean
    var isCurrentSearchResult: Boolean

    /**
     * 变细擦除时"字后面实际的颜色"：高亮色块与当前搜索命中块都画在文字之前，有块色就用块色
     * （可能带透明度，由擦除方先与页面背景合成）。
     * 背景图片的均色不可知，其余场景（纯色页/背景图页/朗读着色）字后面就是页面背景，返回 null。
     */
    fun eraseBgColor(): Int? = when {
        bgImage.isNotEmpty() -> null
        bgColor != null -> bgColor
        isCurrentSearchResult -> ReadBookConfig.currentSearchHitBgColor
        else -> null
    }
}
