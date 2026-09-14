package io.legado.app.help.book

import android.content.Context

/**
 * 标签匹配的统一入口：把用户手动设置的 customTag 与智能标签合并成同一套判定。
 *
 * 书架标签栏、书籍列表筛选都走这里，避免各处重复实现"自定义标签还是智能标签"的分支。
 */
object BookTagMatcher {

    /** 当前生效的智能规则（总开关关闭时为空）。调用方应复用返回值，避免逐本书重复解析。 */
    fun enabledRules(context: Context): List<SmartTag.ResolvedRule> {
        if (!SmartTagConfig.isEnabled(context)) return emptyList()
        val disabled = SmartTagConfig.disabledRuleIds(context)
        return SmartTag.resolve(context).filter { it.id !in disabled }
    }

    /** 书籍是否命中标签 [tag]：customTag 命中，或任一启用规则命中（规则名与 [tag] 同名）。 */
    fun matches(
        tag: String,
        customTag: String?,
        snapshot: SmartTag.Snapshot,
        resolvedRules: List<SmartTag.ResolvedRule>,
    ): Boolean {
        if (BookTagHelper.has(customTag, tag)) return true
        return resolvedRules.any { it.name.equals(tag, ignoreCase = true) && it.match(snapshot) }
    }

    /** 标签栏展示用的智能标签名：仅保留至少命中一本书的规则。 */
    fun matchingNames(
        snapshots: Collection<SmartTag.Snapshot>,
        resolvedRules: List<SmartTag.ResolvedRule>,
    ): List<String> = SmartTag.matchingNames(snapshots, resolvedRules)
}
