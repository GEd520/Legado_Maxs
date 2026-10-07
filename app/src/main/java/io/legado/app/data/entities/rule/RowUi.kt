package io.legado.app.data.entities.rule

data class RowUi(
    val name: String = "",
    val type: String = "text",
    val action: String? = null,
    val chars: List<String?>? = null,
    val default: String? = null,
    var viewName: String? = null,
    val style: FlexChildStyle? = null
) {

    @Suppress("ConstPropertyName")
    object Type {

        const val text = "text"
        const val password = "password"
        const val button = "button"
        const val toggle = "toggle"
        const val select = "select"

    }

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }

    /** 同 [ExploreKind]：候选列表参与判等，避免按本对象做 key 时漏掉候选变化 */
    override fun equals(other: Any?): Boolean {
        if (other is RowUi) {
            return other.name == name
                    && other.type == type
                    && other.action == action
                    && other.chars == chars
                    && other.default == default
                    && other.style == style
        }
        return false
    }

    override fun hashCode(): Int {
        var result = name.hashCode() + type.hashCode()
        result = 31 * result + (action?.hashCode() ?: 0)
        result = 31 * result + (chars?.hashCode() ?: 0)
        result = 31 * result + (default?.hashCode() ?: 0)
        result = 31 * result + (style?.hashCode() ?: 0)
        return result
    }

}