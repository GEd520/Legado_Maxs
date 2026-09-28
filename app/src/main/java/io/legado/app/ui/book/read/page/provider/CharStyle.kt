package io.legado.app.ui.book.read.page.provider

/**
 * 每个字符的高亮样式，由高亮规则整章匹配后填充到字符样式数组。
 *
 * 参考 MD3-main 的实现：排版期用数组下标直接取样式，
 * 取代旧的 SpannableStringBuilder + 逐字符 getSpans 方案，
 * 避免规则较多时大量 Span 分配与查询拖慢章节打开速度。
 */
data class CharStyle(
    val textColor: Int? = null,
    val underlineMode: Int = 0,
    val underlineColor: Int = 0xFF63C37D.toInt(),
    val underlineWidth: Float = 1f,
    val underlineOffset: Float = 2f,
    val underlineSvgPath: String = "",
    val bgColor: Int? = null,
    val bgImage: String = "",
    val bgImageFit: Int = 0,
    val bgImageScale: Float = 1f,
    /** 九宫格分割比例，适配方式为九宫格(bgImageFit=3)时生效 */
    val npLeft: Float = 0.1f,
    val npTop: Float = 0.1f,
    val npRight: Float = 0.1f,
    val npBottom: Float = 0.1f,
    /** 九宫格外扩策略，取值 HighlightRule.BLEED_* */
    val bgBleedMode: Int = 1,
    /** 背景图左间距（em），正数向外撑大、负数向内收 */
    val bgSpacingLeft: Float = 0f,
    /** 背景图右间距（em），正数向外撑大、负数向内收 */
    val bgSpacingRight: Float = 0f,
    /** 背景图上间距（em），正数向外撑大、负数向内收 */
    val bgSpacingTop: Float = 0f,
    /** 背景图下间距（em），正数向外撑大、负数向内收 */
    val bgSpacingBottom: Float = 0f,
    /**
     * 命中字距（px）：规则声明的段首留白。
     *
     * 命中段内字符同样保留原值，真正在哪个字符上让出由 [matchStartsHere] 与折行位置决定
     * （见 [spacingBefore]）——只留段首值的话，命中段跨行时行首字符的留白就取不回来了。
     */
    val letterSpacingBefore: Float = 0f,
    /** 命中字距（px）：规则声明的段尾留白，生效位置见 [matchEndsHere] 与 [spacingAfter] */
    val letterSpacingAfter: Float = 0f,
    /** 本字符是命中段的首字符（留白落在它左侧） */
    val matchStartsHere: Boolean = false,
    /** 本字符是命中段的尾字符（留白落在它右侧） */
    val matchEndsHere: Boolean = false,
    /** 命中行上下行距：是否只给包含命中的行加行距 */
    val lineSpacingEnabled: Boolean = false,
    /** 命中行上方行距（px） */
    val lineSpacingTop: Float = 0f,
    /** 命中行下方行距（px） */
    val lineSpacingBottom: Float = 0f,
    /** 高亮字体路径，空串表示跟随阅读字体 */
    val font: String = "",
) {

    /** 是否设置了命中字距：只影响排版留白，不影响字形绘制 */
    val hasLetterSpacing: Boolean
        get() = letterSpacingBefore > 0f || letterSpacingAfter > 0f

    /**
     * 标记本字符在命中段里的位置：留白属于命中段与邻字之间的空隙，段内字符若也生效
     * 就会被从内部撑开。命中段只有一个字符时两侧都标记。留白值本身保留，不在这里清零。
     */
    fun withMatchBoundary(startOfMatch: Boolean, endOfMatch: Boolean): CharStyle {
        if (!hasLetterSpacing) return this
        return copy(
            matchStartsHere = startOfMatch,
            matchEndsHere = endOfMatch,
        )
    }

    /**
     * 本字符实际让出的左侧留白。
     *
     * 除命中段首字符外，**折行后的行首字符**同样让出：命中段被切开时，切口两侧与左/右边界
     * 之间也要有这一段空隙，否则高亮带在行首紧贴轮廓，与命中段首尾的观感对不上。
     *
     * @param continuesFromPrev 上一字符与本字符属于同一段命中（折行判断，仅行首需要）
     */
    fun spacingBefore(atLineStart: Boolean, continuesFromPrev: Boolean): Float {
        return if (matchStartsHere || (atLineStart && continuesFromPrev)) {
            letterSpacingBefore
        } else {
            0f
        }
    }

    /** 本字符实际让出的右侧留白，折行时行尾字符同样让出（见 [spacingBefore]） */
    fun spacingAfter(atLineEnd: Boolean, continuesToNext: Boolean): Float {
        return if (matchEndsHere || (atLineEnd && continuesToNext)) {
            letterSpacingAfter
        } else {
            0f
        }
    }

    /**
     * 字段级合并重叠规则的样式，与旧 Span 实现中 extractHighlightStyle
     * 对多个重叠 Span 各取所需的行为保持一致：
     * 下划线字段取最后一条带下划线的规则，背景取最后一条带背景的规则，
     * 字色取最后一条指定了字色的规则。
     */
    fun mergedWith(later: CharStyle): CharStyle {
        if (later.underlineMode != 0 &&
            later.bgImage.isNotEmpty() &&
            later.bgColor != null &&
            later.textColor != null
        ) {
            // 整条覆盖时仅补上命中排版留白：重叠规则的留白取较大者，不能随覆盖丢失
            if (!hasLetterSpacing && lineSpacingTop <= 0f && lineSpacingBottom <= 0f) {
                return later
            }
            return later.copy(
                letterSpacingBefore = maxOf(letterSpacingBefore, later.letterSpacingBefore),
                letterSpacingAfter = maxOf(letterSpacingAfter, later.letterSpacingAfter),
                matchStartsHere = matchStartsHere || later.matchStartsHere,
                matchEndsHere = matchEndsHere || later.matchEndsHere,
                lineSpacingEnabled = later.lineSpacingEnabled || lineSpacingEnabled,
                lineSpacingTop = maxOf(lineSpacingTop, later.lineSpacingTop),
                lineSpacingBottom = maxOf(lineSpacingBottom, later.lineSpacingBottom),
            )
        }
        return CharStyle(
            textColor = later.textColor ?: textColor,
            underlineMode = if (later.underlineMode != 0) later.underlineMode else underlineMode,
            underlineColor = if (later.underlineMode != 0) later.underlineColor else underlineColor,
            underlineWidth = if (later.underlineMode != 0) later.underlineWidth else underlineWidth,
            underlineOffset = if (later.underlineMode != 0) later.underlineOffset else underlineOffset,
            underlineSvgPath = if (later.underlineMode != 0) later.underlineSvgPath else underlineSvgPath,
            bgColor = later.bgColor ?: bgColor,
            bgImage = if (later.bgImage.isNotEmpty()) later.bgImage else bgImage,
            bgImageFit = if (later.bgImage.isNotEmpty()) later.bgImageFit else bgImageFit,
            bgImageScale = if (later.bgImage.isNotEmpty()) later.bgImageScale else bgImageScale,
            npLeft = if (later.bgImage.isNotEmpty()) later.npLeft else npLeft,
            npTop = if (later.bgImage.isNotEmpty()) later.npTop else npTop,
            npRight = if (later.bgImage.isNotEmpty()) later.npRight else npRight,
            npBottom = if (later.bgImage.isNotEmpty()) later.npBottom else npBottom,
            bgBleedMode = if (later.bgImage.isNotEmpty()) later.bgBleedMode else bgBleedMode,
            bgSpacingLeft = if (later.bgImage.isNotEmpty()) later.bgSpacingLeft else bgSpacingLeft,
            bgSpacingRight = if (later.bgImage.isNotEmpty()) later.bgSpacingRight else bgSpacingRight,
            bgSpacingTop = if (later.bgImage.isNotEmpty()) later.bgSpacingTop else bgSpacingTop,
            bgSpacingBottom = if (later.bgImage.isNotEmpty()) later.bgSpacingBottom else bgSpacingBottom,
            letterSpacingBefore = maxOf(letterSpacingBefore, later.letterSpacingBefore),
            letterSpacingAfter = maxOf(letterSpacingAfter, later.letterSpacingAfter),
            matchStartsHere = matchStartsHere || later.matchStartsHere,
            matchEndsHere = matchEndsHere || later.matchEndsHere,
            lineSpacingEnabled = later.lineSpacingEnabled || lineSpacingEnabled,
            lineSpacingTop = maxOf(lineSpacingTop, later.lineSpacingTop),
            lineSpacingBottom = maxOf(lineSpacingBottom, later.lineSpacingBottom),
            font = if (later.font.isNotEmpty()) later.font else font,
        )
    }
}

/**
 * 命中字距在**断行测量**中要占的额外宽度（逐字符）。
 *
 * 只有命中段的首/尾字符占宽（[CharStyle.matchStartsHere] / [CharStyle.matchEndsHere]）；
 * 折行切出的行首/行尾留白不在这里——断行时还不知道折在哪，那部分由逐字绘制与
 * [measureLineMatchSpacing] 处理。留白只并入「断行用的宽度副本」，
 * 列位置仍在逐字绘制时让位（见 TextChapterLayout）。
 *
 * @return 与 [size] 等长的每字符额外宽度；没有任何留白时返回 null，调用方可跳过整份拷贝
 */
internal fun Array<CharStyle?>?.matchSpacingWidths(size: Int): FloatArray? {
    if (this == null) return null
    var result: FloatArray? = null
    for (index in 0 until minOf(size, this.size)) {
        val style = this[index] ?: continue
        val extra = (if (style.matchStartsHere) style.letterSpacingBefore else 0f) +
            (if (style.matchEndsHere) style.letterSpacingAfter else 0f)
        if (extra <= 0f) continue
        val array = result ?: FloatArray(size).also { result = it }
        array[index] = extra
    }
    return result
}

/**
 * 逐字绘制时某字符实际让出的左侧留白（px）。
 *
 * @param isLineStart 该字符是否是可视行的首字符
 */
internal fun Array<CharStyle?>?.lineSpacingBefore(charIndex: Int, isLineStart: Boolean): Float {
    val styles = this ?: return 0f
    val style = styles.getOrNull(charIndex) ?: return 0f
    val continuesFromPrev = isLineStart && charIndex > 0 && styles.getOrNull(charIndex - 1) != null
    return style.spacingBefore(isLineStart, continuesFromPrev)
}

/**
 * 逐字绘制时某字符实际让出的右侧留白（px）。
 *
 * @param isLineEnd 该字符是否是可视行的末字符
 */
internal fun Array<CharStyle?>?.lineSpacingAfter(charIndex: Int, isLineEnd: Boolean): Float {
    val styles = this ?: return 0f
    val style = styles.getOrNull(charIndex) ?: return 0f
    val continuesToNext = isLineEnd && styles.getOrNull(charIndex + 1) != null
    return style.spacingAfter(isLineEnd, continuesToNext)
}

/**
 * 一条可视行里命中字距实际占掉的宽度（px）：[lineStart, lineEnd) 内所有生效留白之和，
 * 含命中段被折行切开时行首/行尾让出的部分（与逐字绘制的让位口径必须一致）。
 *
 * 两端对齐算剩余宽度时必须把它减掉：逐字绘制时留白由列位置追加，只按字形宽度算剩余空间
 * 会让这一行整行多出留白那么宽，高亮文字从右侧溢出，再被 [TextChapterLayout] 的越界兜底
 * 反向压缩回去，看起来就是字符挤在一起。
 */
internal fun Array<CharStyle?>?.measureLineMatchSpacing(lineStart: Int, lineEnd: Int): Float {
    val styles = this ?: return 0f
    if (lineEnd <= lineStart) return 0f
    val lastIndex = lineEnd - 1
    var spacing = 0f
    for (index in lineStart until minOf(lineEnd, styles.size)) {
        spacing += styles.lineSpacingBefore(index, index == lineStart)
        spacing += styles.lineSpacingAfter(index, index == lastIndex)
    }
    return spacing
}
