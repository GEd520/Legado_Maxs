package io.legado.app.help.book

import android.content.Context
import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.data.entities.Book

/**
 * 智能标签引擎。
 *
 * 根据书籍**已有元数据**（书籍类型、阅读进度、章节数量、更新状态）生成虚拟标签：
 * 标签不写入 books 表，因此无需数据库迁移，也不会覆盖用户手动设置的 customTag。
 *
 * 规则 id 是稳定标识（用于持久化开关状态），展示名与说明走字符串资源以支持多语言，
 * 见 [ResolvedRule]。
 */
object SmartTag {

    /**
     * 规则判定所需的书籍字段快照。
     *
     * 只有这些字段参与智能标签判定，因此书架轻量查询结果（[BookShelfDisplay]、
     * [BookTagInfo]）也能直接使用，无需再查完整 [Book]。
     */
    data class Snapshot(
        val type: Int,
        val origin: String,
        val totalChapterNum: Int,
        val durChapterIndex: Int,
        val durChapterPos: Int,
        val lastCheckCount: Int,
        val canUpdate: Boolean,
    )

    private fun Snapshot.isLocal(): Boolean {
        if (type == 0) {
            return origin == BookType.localTag || origin.startsWith(BookType.webDavTag)
        }
        return type and BookType.local > 0
    }

    /** 一条智能标签规则，[match] 为纯函数，便于单元测试。 */
    data class Rule(
        val id: String,
        @StringRes val nameRes: Int,
        @StringRes val descriptionRes: Int,
        val match: (Snapshot) -> Boolean,
    )

    /** 规则 + 已本地化的名称/说明，供 UI 与筛选复用，避免逐本书重复解析字符串资源。 */
    data class ResolvedRule(
        val id: String,
        val name: String,
        val description: String,
        val match: (Snapshot) -> Boolean,
    )

    val rules: List<Rule> = listOf(
        Rule(
            "audio",
            R.string.smart_tag_rule_audio,
            R.string.smart_tag_rule_audio_desc,
        ) { it.type and BookType.audio > 0 },
        Rule(
            "image",
            R.string.smart_tag_rule_image,
            R.string.smart_tag_rule_image_desc,
        ) { it.type and BookType.image > 0 },
        Rule(
            "video",
            R.string.smart_tag_rule_video,
            R.string.smart_tag_rule_video_desc,
        ) { it.type and BookType.video > 0 },
        Rule(
            "local",
            R.string.smart_tag_rule_local,
            R.string.smart_tag_rule_local_desc,
        ) { it.isLocal() },
        Rule(
            "online",
            R.string.smart_tag_rule_online,
            R.string.smart_tag_rule_online_desc,
        ) { !it.isLocal() },
        Rule(
            "update_error",
            R.string.smart_tag_rule_update_error,
            R.string.smart_tag_rule_update_error_desc,
        ) { it.type and BookType.updateError > 0 },
        Rule(
            "finished",
            R.string.smart_tag_rule_finished,
            R.string.smart_tag_rule_finished_desc,
        ) { it.totalChapterNum > 0 && it.durChapterIndex >= it.totalChapterNum - 1 },
        Rule(
            "reading",
            R.string.smart_tag_rule_reading,
            R.string.smart_tag_rule_reading_desc,
        ) {
            it.totalChapterNum > 0 &&
                it.durChapterIndex > 0 &&
                it.durChapterIndex < it.totalChapterNum - 1
        },
        Rule(
            "unread",
            R.string.smart_tag_rule_unread,
            R.string.smart_tag_rule_unread_desc,
        ) { it.totalChapterNum > 0 && it.durChapterIndex <= 0 && it.durChapterPos <= 0 },
        Rule(
            "very_long",
            R.string.smart_tag_rule_very_long,
            R.string.smart_tag_rule_very_long_desc,
        ) { it.totalChapterNum >= 1000 },
        Rule(
            "long",
            R.string.smart_tag_rule_long,
            R.string.smart_tag_rule_long_desc,
        ) { it.totalChapterNum in 500..999 },
        Rule(
            "medium",
            R.string.smart_tag_rule_medium,
            R.string.smart_tag_rule_medium_desc,
        ) { it.totalChapterNum in 200..499 },
        Rule(
            "short",
            R.string.smart_tag_rule_short,
            R.string.smart_tag_rule_short_desc,
        ) { it.totalChapterNum in 1..49 },
        Rule(
            "has_update",
            R.string.smart_tag_rule_has_update,
            R.string.smart_tag_rule_has_update_desc,
        ) { it.lastCheckCount > 0 },
        Rule(
            "cannot_update",
            R.string.smart_tag_rule_cannot_update,
            R.string.smart_tag_rule_cannot_update_desc,
        ) { !it.canUpdate },
    )

    val ruleIds: List<String> = rules.map { it.id }

    fun ruleById(id: String): Rule? = rules.firstOrNull { it.id == id }

    /** 解析出全部规则的本地化名称与说明。 */
    fun resolve(context: Context): List<ResolvedRule> = rules.map { it.resolve(context) }

    /** 返回 [snapshot] 命中的所有规则（按 [rules] 声明顺序）。 */
    fun matchingRules(
        snapshot: Snapshot,
        resolvedRules: List<ResolvedRule>,
    ): List<ResolvedRule> = resolvedRules.filter { it.match(snapshot) }

    /** 返回 [snapshots] 中至少有一本书命中的规则名（按 [rules] 声明顺序）。 */
    fun matchingNames(
        snapshots: Collection<Snapshot>,
        resolvedRules: List<ResolvedRule>,
    ): List<String> = resolvedRules
        .filter { rule -> snapshots.any { rule.match(it) } }
        .map { it.name }
}

/** 以当前语言解析规则名称与说明。 */
fun SmartTag.Rule.resolve(context: Context): SmartTag.ResolvedRule = SmartTag.ResolvedRule(
    id = id,
    name = context.getString(nameRes),
    description = context.getString(descriptionRes),
    match = match,
)

/** 生成智能标签判定所需的字段快照。 */
fun Book.toSmartTagSnapshot(): SmartTag.Snapshot = SmartTag.Snapshot(
    type = type,
    origin = origin,
    totalChapterNum = totalChapterNum,
    durChapterIndex = durChapterIndex,
    durChapterPos = durChapterPos,
    lastCheckCount = lastCheckCount,
    canUpdate = canUpdate,
)

fun BookShelfDisplay.toSmartTagSnapshot(): SmartTag.Snapshot = SmartTag.Snapshot(
    type = type,
    origin = origin,
    totalChapterNum = totalChapterNum,
    durChapterIndex = durChapterIndex,
    durChapterPos = durChapterPos,
    lastCheckCount = lastCheckCount,
    canUpdate = canUpdate,
)

fun BookTagInfo.toSmartTagSnapshot(): SmartTag.Snapshot = SmartTag.Snapshot(
    type = type,
    origin = origin,
    totalChapterNum = totalChapterNum,
    durChapterIndex = durChapterIndex,
    durChapterPos = durChapterPos,
    lastCheckCount = lastCheckCount,
    canUpdate = canUpdate,
)
