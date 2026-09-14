package io.legado.app.help.book

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.removePref

/**
 * 智能标签配置存储。
 *
 * 只持久化"被关闭的规则 id"（黑名单），未记录的规则一律视为开启，
 * 这样后续新增内置规则时不会被老的配置快照静默隐藏。
 */
object SmartTagConfig {

    /** 智能标签总开关。 */
    fun isEnabled(context: Context): Boolean = context.getPrefBoolean(PreferKey.smartTagsEnabled, true)

    fun setEnabled(context: Context, enabled: Boolean) = context.putPrefBoolean(PreferKey.smartTagsEnabled, enabled)

    /** 被用户关闭的规则 id 集合（返回副本，修改不会写回偏好）。 */
    fun disabledRuleIds(context: Context): Set<String> = context.getPrefStringSet(PreferKey.smartTagsDisabledRules)?.toSet().orEmpty()

    fun isRuleEnabled(context: Context, ruleId: String): Boolean = ruleId !in disabledRuleIds(context)

    fun setRuleEnabled(context: Context, ruleId: String, enabled: Boolean) {
        val disabled = disabledRuleIds(context).toMutableSet()
        if (enabled) disabled.remove(ruleId) else disabled.add(ruleId)
        if (disabled.isEmpty()) {
            context.removePref(PreferKey.smartTagsDisabledRules)
        } else {
            context.putPrefStringSet(PreferKey.smartTagsDisabledRules, disabled)
        }
    }
}
