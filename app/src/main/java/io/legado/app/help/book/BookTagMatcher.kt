package io.legado.app.help.book

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * 标签匹配的统一入口：把用户手动设置的 customTag 与智能标签合并成同一套判定。
 *
 * 书架标签栏、书籍列表筛选都走这里，避免各处重复实现"自定义标签还是智能标签"的分支。
 */
object BookTagMatcher {

    /** 书籍信息区展示标签时的菱形前缀，用于与字数、分类等其他信息区分。 */
    const val TAG_MARKER = "◆"

    /** 规则解析结果的缓存快照（不可变，整体替换，避免并发读到半更新状态）。 */
    private data class RuleCache(val key: String, val rules: List<SmartTag.ResolvedRule>)

    @Volatile
    private var ruleCache: RuleCache? = null

    /** 当前生效的智能规则（总开关关闭时为空）。结果带缓存，可逐本书调用。 */
    fun enabledRules(context: Context): List<SmartTag.ResolvedRule> {
        val key = "${SmartTagConfig.revision}|${localeKey(context)}"
        ruleCache?.let { if (it.key == key) return it.rules }
        val rules = if (SmartTagConfig.isEnabled(context)) {
            val disabled = SmartTagConfig.disabledRuleIds(context)
            SmartTag.resolve(context).filter { it.id !in disabled }
        } else {
            emptyList()
        }
        ruleCache = RuleCache(key, rules)
        return rules
    }

    /** 书籍自身的标签名：用户自定义标签 + 命中的智能标签（自定义标签在前，大小写不敏感去重）。 */
    fun bookTagNames(
        customTag: String?,
        snapshot: SmartTag.Snapshot,
        resolvedRules: List<SmartTag.ResolvedRule>,
    ): List<String> {
        val names = BookTagHelper.parse(customTag).toMutableList()
        if (resolvedRules.isEmpty()) return names
        val keys = names.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        for (rule in resolvedRules) {
            if (!rule.match(snapshot)) continue
            if (keys.add(rule.name.lowercase(Locale.ROOT))) names.add(rule.name)
        }
        return names
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

    /**
     * 统计每个标签命中的书籍数量，供标签栏 "标签名·数量" 展示。
     *
     * [parsedCustomTags] 与 [snapshots] 必须与书籍列表一一对应（同序），前者为
     * [BookTagHelper.parseSet] 的结果（调用方复用，避免重复切分字符串）。
     * 数量为"自定义标签或智能标签"的并集，与 [matches] 口径一致。
     *
     * 实现为对书籍的**单趟遍历**：每本书只遍历自身标签集合与"名字命中标签栏的规则"，
     * 复杂度 O(书籍数 × (本书标签数 + 启用规则数))，不会随标签栏标签数量线性放大。
     */
    fun countMatches(
        tags: List<String>,
        parsedCustomTags: List<Set<String>>,
        snapshots: List<SmartTag.Snapshot>,
        resolvedRules: List<SmartTag.ResolvedRule>,
    ): Map<String, Int> {
        if (tags.isEmpty() || snapshots.isEmpty()) return emptyMap()
        val tagByKey = tags.associateBy { it.lowercase(Locale.ROOT) }
        // 只保留标签栏里确实有的智能规则，其余规则无需逐本书判定
        val ruleByKey = resolvedRules
            .filter { it.name.lowercase(Locale.ROOT) in tagByKey }
            .associateBy { it.name.lowercase(Locale.ROOT) }
        val counts = HashMap<String, Int>(tags.size)
        for (index in snapshots.indices) {
            val customKeys = parsedCustomTags.getOrNull(index).orEmpty()
            for (key in customKeys) {
                val tag = tagByKey[key] ?: continue
                counts[tag] = (counts[tag] ?: 0) + 1
            }
            if (ruleByKey.isEmpty()) continue
            val snapshot = snapshots[index]
            for ((key, rule) in ruleByKey) {
                val tag = tagByKey[key] ?: continue
                if (key in customKeys) continue
                if (rule.match(snapshot)) counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return tags.associateWith { counts[it] ?: 0 }
    }

    /** 当前语言标识，仅用于判断 [ruleCache] 是否失效（语言变化后规则名需重新解析）。 */
    private fun localeKey(context: Context): String {
        val configuration = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales[0].toString()
        } else {
            @Suppress("DEPRECATION")
            configuration.locale.toString()
        }
    }
}
