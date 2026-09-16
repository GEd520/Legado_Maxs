package io.legado.app.model.blockrule

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import io.legado.app.utils.RegexCache
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import java.util.UUID

/**
 * 屏蔽规则存储管理
 *
 * 使用 SharedPreferences + JSON 序列化存储屏蔽规则列表，
 * 提供规则的加载、保存、过滤和清洗功能。
 * 内置内存缓存避免频繁反序列化。
 *
 * 性能优化：
 * - [cachedRules] 缓存反序列化结果，避免频繁读取 SharedPreferences
 * - [cachedCompiled] 缓存已编译的 [CompiledBlockRule] 列表（含预解析 scope + 预编译正则），
 *   避免每次过滤都重新编译
 */
object BlockRuleStore {

    /** 内存缓存，避免频繁读取 SharedPreferences 和反序列化 */
    @Volatile
    private var cachedRules: List<BlockRule>? = null

    /**
     * 已编译的屏蔽规则缓存（仅包含 enabled && pattern 非空的规则）
     * 预编译了 scope 字符串解析和正则表达式，避免每次匹配时重复计算
     */
    @Volatile
    private var cachedCompiled: List<CompiledBlockRule>? = null

    /**
     * 加载所有屏蔽规则
     * 优先从缓存读取，缓存未命中时从 SharedPreferences 反序列化
     */
    fun load(context: Context): MutableList<BlockRule> {
        cachedRules?.let { return it.toMutableList() }
        val stored = context.getPrefString(PreferKey.blockRuleItems)
        if (stored.isNullOrBlank()) {
            return mutableListOf()
        }
        // 混淆版本遗留数据：release 包未 keep BlockRule 时 GSON 以 a/b 等混淆名序列化，
        // 键名无法映射到当前字段；但字段写出顺序遵循声明顺序且 null 字段省略，
        // 可按「位置 + 类型签名」恢复。恢复失败时备份原始数据后再清除。
        if (isObfuscatedLegacyJson(stored)) {
            val recovered = recoverObfuscatedLegacyJson(stored)
            if (recovered != null) {
                val sanitized = recovered.map { sanitizeRule(it) }
                cachedRules = sanitized
                // 立即以规范字段名回写，避免下次加载再次走恢复流程
                context.putPrefString(PreferKey.blockRuleItems, GSON.toJson(sanitized))
                BlockRuleGroupStore.ensureFromRules(context, sanitized)
                return sanitized.toMutableList()
            }
            // 无法恢复：保留原始备份，避免数据彻底丢失
            context.putPrefString(PreferKey.blockRuleItemsLegacyBackup, stored)
            context.removePref(PreferKey.blockRuleItems)
            return mutableListOf()
        }
        val rules = GSON.fromJsonArray<BlockRule>(stored).getOrNull()?.toMutableList()
        if (rules != null) {
            val sanitized = rules.map { sanitizeRule(it) }
            cachedRules = sanitized
            BlockRuleGroupStore.ensureFromRules(context, sanitized)
            return sanitized.toMutableList()
        }
        return mutableListOf()
    }

    /**
     * 加载已启用且有匹配模式的规则（原始 BlockRule）
     */
    fun loadEnabled(context: Context): List<BlockRule> = load(context).filter { it.enabled && it.pattern.isNotBlank() }

    /**
     * 加载已编译的屏蔽规则列表（缓存）
     *
     * 每条规则已预解析 scope 字符串并预编译正则表达式，
     * 后续匹配不再需要 split/trim/filter 或重复编译正则。
     */
    private fun loadCompiled(context: Context): List<CompiledBlockRule> {
        cachedCompiled?.let { return it }
        val enabled = loadEnabled(context)
        val compiled = enabled.map { it.compile() }
        cachedCompiled = compiled
        return compiled
    }

    /**
     * 保存规则列表
     * 同时更新缓存和 SharedPreferences，并同步分组信息
     * 自动处理 id 重复的情况，确保每个规则的 id 都是唯一的
     */
    fun save(context: Context, rules: List<BlockRule>) {
        // 检查并修复 id 重复的情况
        val usedIds = mutableSetOf<String>()
        val normalized = rules.map { rule ->
            var sanitized = sanitizeRule(rule)
            // 如果 id 已被使用，生成新的唯一 id
            while (sanitized.id in usedIds) {
                sanitized = sanitized.copyWithNewId()
            }
            usedIds.add(sanitized.id)
            sanitized
        }
        cachedRules = normalized
        cachedCompiled = null
        context.putPrefString(PreferKey.blockRuleItems, GSON.toJson(normalized))
        BlockRuleGroupStore.ensureFromRules(context, normalized)
    }

    /**
     * 核心过滤方法：返回被屏蔽规则过滤后的书籍列表
     *
     * 优化策略：
     * 1. 使用预编译的 CompiledBlockRule，避免每次 split/trim/filter 和正则编译
     * 2. 先过滤作用域匹配的规则，避免对每本书都检查作用域
     */
    fun filterBooks(context: Context, books: List<SearchBook>, sourceUrl: String): List<SearchBook> {
        if (!context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) return books
        val rules = loadCompiled(context)
        if (rules.isEmpty()) return books

        val applicableRules = rules.filter { it.matchesScope(sourceUrl) }
        if (applicableRules.isEmpty()) return books

        return books.filterNot { book ->
            applicableRules.any { rule -> rule.matches(book) }
        }
    }

    /**
     * 搜索结果过滤：每本书有独立的书源URL，按各自origin匹配作用域
     * 优化：使用groupBy减少规则匹配次数
     */
    fun filterSearchBooks(context: Context, books: List<SearchBook>): List<SearchBook> {
        if (!context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) return books
        val rules = loadCompiled(context)
        if (rules.isEmpty()) return books

        // 按书源URL分组，避免对每本书都检查作用域
        val booksBySource = books.groupBy { it.origin }
        val result = mutableListOf<SearchBook>()

        for ((sourceUrl, sourceBooks) in booksBySource) {
            val applicableRules = rules.filter { it.matchesScope(sourceUrl) }
            if (applicableRules.isEmpty()) {
                result.addAll(sourceBooks)
            } else {
                result.addAll(
                    sourceBooks.filterNot { book ->
                        applicableRules.any { rule -> rule.matches(book) }
                    },
                )
            }
        }

        return result
    }

    /**
     * RSS文章过滤：标题匹配 SCOPE_RSS_TITLE，时间匹配 SCOPE_RSS_TIME
     * 使用 rssScope 字段匹配订阅源作用域
     * 优化：先过滤作用域匹配的规则，减少不必要的匹配计算
     */
    fun filterRssArticles(context: Context, articles: List<RssArticle>, sourceUrl: String): List<RssArticle> {
        if (!context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) return articles
        val rules = loadCompiled(context)
        if (rules.isEmpty()) return articles

        val applicableRules = rules.filter { it.matchesRssScope(sourceUrl) }
        if (applicableRules.isEmpty()) return articles

        return articles.filterNot { article ->
            applicableRules.any { rule -> rule.matchesRssArticle(article) }
        }
    }

    /**
     * 获取实际匹配到书籍的规则列表
     * 返回在指定书籍列表和书源下，至少匹配了一本书的规则
     * 使用编译后的规则进行匹配，提升性能
     */
    fun getMatchedRules(context: Context, books: List<SearchBook>, sourceUrl: String): List<BlockRule> {
        val compiled = loadCompiled(context)
        if (compiled.isEmpty() || books.isEmpty()) return emptyList()
        val allRules = loadEnabled(context)
        val matchedIds = compiled
            .filter { it.matchesScope(sourceUrl) && books.any { book -> it.matches(book) } }
            .map { it.id }
            .toSet()
        return allRules.filter { it.id in matchedIds }
    }

    /**
     * 获取实际匹配到RSS文章的规则列表
     * 返回在指定文章列表和订阅源下，至少匹配了一篇文章的规则
     * 使用编译后的规则进行匹配，提升性能
     */
    fun getMatchedRssRules(context: Context, articles: List<RssArticle>, sourceUrl: String): List<BlockRule> {
        val compiled = loadCompiled(context)
        if (compiled.isEmpty() || articles.isEmpty()) return emptyList()
        val allRules = loadEnabled(context)
        val matchedIds = compiled
            .filter { it.matchesRssScope(sourceUrl) && articles.any { article -> it.matchesRssArticle(article) } }
            .map { it.id }
            .toSet()
        return allRules.filter { it.id in matchedIds }
    }

    /**
     * 过滤书籍并同时收集匹配到的规则（单次遍历）
     *
     * 返回 [FilterAndMatchResult]，包含过滤后的书籍列表和匹配到的规则列表。
     * 用于 [ExploreShowViewModel.applyBlockRules] 等场景，避免对同一批数据遍历两次。
     */
    fun filterAndCollectMatched(
        context: Context,
        books: List<SearchBook>,
        sourceUrl: String,
    ): FilterAndMatchResult {
        if (!context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) {
            return FilterAndMatchResult(books, emptyList())
        }
        val compiledRules = loadCompiled(context)
        if (compiledRules.isEmpty() || books.isEmpty()) {
            return FilterAndMatchResult(books, emptyList())
        }

        val applicableRules = compiledRules.filter { it.matchesScope(sourceUrl) }
        if (applicableRules.isEmpty()) {
            return FilterAndMatchResult(books, emptyList())
        }

        val matchedRuleIds = mutableSetOf<String>()
        val filteredBooks = books.filterNot { book ->
            // 逐条规则匹配，命中则记录规则 ID 并过滤该书
            var matched = false
            for (rule in applicableRules) {
                if (rule.matches(book)) {
                    matchedRuleIds.add(rule.id)
                    matched = true
                    // 不 break：记录所有命中规则，确保 UI 触发规则报告完整
                }
            }
            matched
        }

        // 从原始规则列表中按 ID 找回对应的 BlockRule
        val allRules = loadEnabled(context)
        val matchedRules = allRules.filter { it.id in matchedRuleIds }

        return FilterAndMatchResult(filteredBooks, matchedRules)
    }

    /** 过滤 + 匹配结果 */
    data class FilterAndMatchResult(
        val filteredBooks: List<SearchBook>,
        val matchedRules: List<BlockRule>,
    )

    /** 清除缓存，下次加载时重新从 SharedPreferences 读取 */
    fun invalidateCache() {
        cachedRules = null
        cachedCompiled = null
        RegexCache.clear() // 同时清除正则表达式缓存，避免旧规则残留
    }

    /**
     * 清洗规则数据，确保字段合法
     * 处理缺失字段、空值、越界值等情况
     */
    fun sanitizeRule(
        rule: BlockRule,
        fallbackGroup: String = BlockRuleGroupStore.DEFAULT_GROUP,
    ): BlockRule {
        val name = runCatching { rule.name }.getOrNull().orEmpty()
        val pattern = runCatching { rule.pattern }.getOrNull().orEmpty()
        val group = runCatching { rule.group }.getOrNull().orEmpty().ifBlank { fallbackGroup }
        val id = runCatching { rule.id }.getOrNull().orEmpty().ifBlank {
            UUID.randomUUID().toString()
        }
        val scope = runCatching { rule.scope }.getOrNull()?.takeIf { it.isNotBlank() }
        val rssScope = runCatching { rule.rssScope }.getOrNull()?.takeIf { it.isNotBlank() }

        // 处理作用范围字段，包含旧版数据迁移
        var bookScope = runCatching { rule.targetScope }.getOrDefault(0)
        var rssScopeFlags = runCatching { rule.rssTargetScope }.getOrDefault(0)

        // 迁移旧版数据：旧版 targetScope 同时包含书源和订阅源的位标志
        // 仅当 rssTargetScope 为 0（新字段未设置）且 targetScope 含旧版 RSS 特有高位（SCOPE_RSS_TIME_LEGACY=16）时才迁移。
        if (rssScopeFlags == 0 && (bookScope and BlockRule.SCOPE_RSS_TIME_LEGACY) != 0) {
            // 旧版 SCOPE_TITLE 同时表示书源标题和订阅源标题
            if ((bookScope and BlockRule.SCOPE_TITLE) != 0) {
                rssScopeFlags = rssScopeFlags or BlockRule.SCOPE_RSS_TITLE
            }
            // 旧版 SCOPE_INTRO 同时表示书源简介和订阅源描述
            if ((bookScope and BlockRule.SCOPE_INTRO) != 0) {
                rssScopeFlags = rssScopeFlags or BlockRule.SCOPE_RSS_INTRO
            }
            // 旧版 SCOPE_RSS_TIME_LEGACY (bit 16) 仅用于订阅源
            if ((bookScope and BlockRule.SCOPE_RSS_TIME_LEGACY) != 0) {
                rssScopeFlags = rssScopeFlags or BlockRule.SCOPE_RSS_TIME
                bookScope = bookScope and BlockRule.SCOPE_RSS_TIME_LEGACY.inv() // 清除旧版 RSS 位
            }
        }

        // 清除已废弃的 SCOPE_RSS_INTRO 位（订阅源简介无实际内容）
        rssScopeFlags = rssScopeFlags and BlockRule.SCOPE_RSS_INTRO.inv()

        bookScope = bookScope.coerceIn(0, BlockRule.SCOPE_BOOK_ALL)
        rssScopeFlags = rssScopeFlags.coerceIn(0, BlockRule.SCOPE_RSS_ALL)

        // 后端安全验证：作用范围位掩码为零时，对应的指定源列表无意义，予以清除
        val validatedScope = if (bookScope == 0) null else scope
        val validatedRssScope = if (rssScopeFlags == 0) null else rssScope

        return BlockRule(
            id = id,
            name = name,
            pattern = pattern,
            isRegex = runCatching { rule.isRegex }.getOrDefault(false),
            group = group,
            targetScope = bookScope,
            rssTargetScope = rssScopeFlags,
            enabled = runCatching { rule.enabled }.getOrDefault(true),
            scope = validatedScope,
            rssScope = validatedRssScope,
        )
    }

    /** BlockRule 的规范字段名，用于识别混淆版本写出的损坏 JSON */
    private val canonicalFieldNames = setOf(
        "id", "name", "pattern", "isRegex", "group", "targetScope",
        "rssTargetScope", "enabled", "scope", "rssScope",
    )

    /**
     * 检测是否为混淆版本遗留的损坏规则数据。
     *
     * 历史版本未 keep BlockRule 字段名，release 包里 GSON 以 a/b 等混淆名写出到
     * SharedPreferences；升级后这些键无法映射到当前字段。只要所有条目都不含任何
     * 规范字段名，即判定为混淆版本遗留数据（正常数据至少会有 id/name/pattern 键）。
     */
    private fun isObfuscatedLegacyJson(stored: String): Boolean {
        return runCatching {
            val element = JsonParser.parseString(stored)
            if (!element.isJsonArray) return@runCatching false
            val entries = element.asJsonArray
            if (entries.isEmpty) return@runCatching false
            entries.all { entry ->
                !entry.isJsonObject || entry.asJsonObject.keySet().none { it in canonicalFieldNames }
            }
        }.getOrDefault(false)
    }

    /**
     * 混淆版本遗留数据的字段声明顺序（历史版本）。
     *
     * GSON 反射序列化按字段声明顺序写出、null 字段省略，混淆只改键名不改顺序，
     * 因此可按「位置 + 类型签名」唯一还原字段含义：
     * - BlockRule（09e7460e9e 起）：id,name,pattern,isRegex,group,targetScope,
     *   rssTargetScope,enabled,scope?,rssScope? → sssbsiib + 尾部 0~2 个 s
     * - ExploreBlockRule（更早版本）：id,name,pattern,isRegex,group,targetScope,
     *   enabled,scope? → sssbsib + 尾部 0~1 个 s
     *
     * 两种签名的布尔/整型位置互不重叠，不会混淆。
     * 缺失的尾部字段只可能是可空的 scope/rssScope。
     */
    private val legacyShapes: List<List<String>> = listOf(
        listOf(
            "id", "name", "pattern", "isRegex", "group",
            "targetScope", "rssTargetScope", "enabled", "scope", "rssScope",
        ),
        listOf(
            "id", "name", "pattern", "isRegex", "group",
            "targetScope", "enabled", "scope",
        ),
    )

    /** 可空字符串字段，允许在序列化时因 null 而缺失 */
    private val nullableShapeFields = setOf("scope", "rssScope")

    /** JsonPrimitive 的类型签名字符：s=字符串 b=布尔 i=整型 ?=其他（不匹配） */
    private fun typeCharOf(value: JsonElement?): Char {
        if (value == null || !value.isJsonPrimitive) return '?'
        val prim = value.asJsonPrimitive
        return when {
            prim.isBoolean -> 'b'
            prim.isNumber -> 'i'
            prim.isString -> 's'
            else -> '?'
        }
    }

    /**
     * 尝试恢复混淆版本遗留的规则 JSON。
     *
     * 逐条按「位置 + 类型签名」匹配历史字段布局并还原字段值；
     * 任意一条无法识别则整体放弃（返回 null），由调用方走备份+清除路径，
     * 避免产生半恢复的混合数据。
     */
    private fun recoverObfuscatedLegacyJson(stored: String): List<BlockRule>? {
        return runCatching {
            val element = JsonParser.parseString(stored)
            if (!element.isJsonArray) return@runCatching null
            val recovered = ArrayList<BlockRule>()
            for (entry in element.asJsonArray) {
                if (!entry.isJsonObject) return@runCatching null
                val obj = entry.asJsonObject
                val shape = matchLegacyShape(obj) ?: return@runCatching null
                recovered.add(buildFromShape(obj, shape))
            }
            recovered
        }.getOrNull()
    }

    /** 匹配条目的字段布局；无匹配返回 null */
    private fun matchLegacyShape(obj: JsonObject): List<String>? {
        val types = obj.keySet().map { typeCharOf(obj.get(it)) }
        for (shape in legacyShapes) {
            val n = types.size
            if (n > shape.size) continue
            if (n == 0) continue
            // 已写出的字段必须逐位匹配声明顺序上的类型
            if ((0 until n).any { typeCharOfShapeField(shape[it]) != types[it] }) continue
            // 缺失的尾部字段必须全部是可空字符串字段（GSON 省略 null）
            if ((n until shape.size).all { shape[it] in nullableShapeFields }) return shape
        }
        return null
    }

    /** shape 字段名对应的期望类型签名 */
    private fun typeCharOfShapeField(field: String): Char = when (field) {
        "isRegex", "enabled" -> 'b'
        "targetScope", "rssTargetScope" -> 'i'
        else -> 's'
    }

    /** 按匹配到的布局，从混淆键名条目还原 BlockRule */
    private fun buildFromShape(obj: JsonObject, shape: List<String>): BlockRule {
        val values = HashMap<String, JsonElement?>()
        obj.keySet().forEachIndexed { index, key ->
            values[shape[index]] = obj.get(key)
        }
        fun stringOf(name: String): String? =
            values[name]?.takeIf { it.isJsonPrimitive }?.asString
        fun booleanOf(name: String): Boolean =
            values[name]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        fun intOf(name: String): Int =
            values[name]?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        return BlockRule(
            id = stringOf("id").orEmpty(),
            name = stringOf("name").orEmpty(),
            pattern = stringOf("pattern").orEmpty(),
            isRegex = booleanOf("isRegex"),
            group = stringOf("group").orEmpty(),
            targetScope = intOf("targetScope"),
            rssTargetScope = intOf("rssTargetScope"),
            enabled = booleanOf("enabled"),
            scope = stringOf("scope"),
            rssScope = stringOf("rssScope"),
        )
    }
}
