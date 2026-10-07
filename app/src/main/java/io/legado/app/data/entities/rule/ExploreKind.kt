package io.legado.app.data.entities.rule

/**
 * 发现分类
 */
data class ExploreKind(
    val title: String = "",
    val url: String? = null,
    val type: String = "url",
    val action: String? = null,
    val chars: List<String?>? = null,
    val default: String? = null,
    var viewName: String? = null,
    val style: FlexChildStyle? = null
) {

    @Suppress("ConstPropertyName")
    object Type {

        const val url = "url"
        const val html = "html"
        const val text = "text"
        const val button = "button"
        const val toggle = "toggle"
        const val select = "select"

    }

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }

    /**
     * 相等判定必须覆盖所有会影响展示与候选的字段。
     *
     * `chars` 尤其不能漏：书源重新求值后，同一个"平台"项可能只有候选列表变了
     * （切换"模式"时"平台"的 36 个平台会换成 5 个短剧平台，其余字段一模一样），
     * 漏掉它会让 Compose 侧按 `remember(key)` 缓存的派生值一直复用旧内容。
     * 类型也必须是 `List`——数组按引用比较，判等结果取决于实例是否同一个，无法依赖。
     *
     * `viewName` 是有意排除的：它是 `var`，且展示文案由调用方按各自的 key 单独求值。
     */
    override fun equals(other: Any?): Boolean {
        if (other is ExploreKind) {
            return other.title == title
                    && other.type == type
                    && other.url == url
                    && other.action == action
                    && other.chars == chars
                    && other.default == default
                    && other.style == style
        }
        return false
    }

    override fun hashCode(): Int {
        var result = title.hashCode() + type.hashCode()
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (action?.hashCode() ?: 0)
        result = 31 * result + (chars?.hashCode() ?: 0)
        result = 31 * result + (default?.hashCode() ?: 0)
        result = 31 * result + (style?.hashCode() ?: 0)
        return result
    }

}
