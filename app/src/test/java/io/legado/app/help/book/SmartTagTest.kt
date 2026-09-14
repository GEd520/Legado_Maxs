package io.legado.app.help.book

import io.legado.app.constant.BookType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SmartTag] 内置规则判定单元测试。
 */
class SmartTagTest {

    @Suppress("LongParameterList")
    private fun snapshot(
        type: Int = BookType.text,
        origin: String = "",
        totalChapterNum: Int = 0,
        durChapterIndex: Int = 0,
        durChapterPos: Int = 0,
        lastCheckCount: Int = 0,
        canUpdate: Boolean = true,
    ) = SmartTag.Snapshot(
        type = type,
        origin = origin,
        totalChapterNum = totalChapterNum,
        durChapterIndex = durChapterIndex,
        durChapterPos = durChapterPos,
        lastCheckCount = lastCheckCount,
        canUpdate = canUpdate,
    )

    private fun matches(ruleId: String, snapshot: SmartTag.Snapshot): Boolean {
        val rule = SmartTag.ruleById(ruleId)
        assertTrue("规则 $ruleId 不存在", rule != null)
        return rule!!.match(snapshot)
    }

    @Test
    fun `rule ids are unique`() {
        assertEquals(SmartTag.ruleIds.size, SmartTag.ruleIds.distinct().size)
    }

    @Test
    fun `unknown rule id returns null`() {
        assertNull(SmartTag.ruleById("not_exist"))
    }

    @Test
    fun `type rules`() {
        assertTrue(matches("audio", snapshot(type = BookType.audio)))
        assertFalse(matches("audio", snapshot(type = BookType.text)))
        assertTrue(matches("image", snapshot(type = BookType.image)))
        assertTrue(matches("video", snapshot(type = BookType.video)))
        assertTrue(matches("update_error", snapshot(type = BookType.updateError)))
    }

    @Test
    fun `local book detected by type or origin`() {
        assertTrue(matches("local", snapshot(type = BookType.local)))
        assertTrue(matches("local", snapshot(type = 0, origin = BookType.localTag)))
        assertFalse(matches("local", snapshot(type = BookType.text, origin = "https://a.com")))
        assertTrue(matches("online", snapshot(type = BookType.text, origin = "https://a.com")))
        assertFalse(matches("online", snapshot(type = BookType.local)))
    }

    @Test
    fun `reading progress rules are mutually exclusive`() {
        val unread = snapshot(totalChapterNum = 100, durChapterIndex = 0, durChapterPos = 0)
        assertTrue(matches("unread", unread))
        assertFalse(matches("reading", unread))
        assertFalse(matches("finished", unread))

        val reading = snapshot(totalChapterNum = 100, durChapterIndex = 50)
        assertTrue(matches("reading", reading))
        assertFalse(matches("unread", reading))
        assertFalse(matches("finished", reading))

        val finished = snapshot(totalChapterNum = 100, durChapterIndex = 99)
        assertTrue(matches("finished", finished))
        assertFalse(matches("reading", finished))
        assertFalse(matches("unread", finished))
    }

    @Test
    fun `no chapter info means no progress rules`() {
        val empty = snapshot(totalChapterNum = 0)
        assertFalse(matches("unread", empty))
        assertFalse(matches("reading", empty))
        assertFalse(matches("finished", empty))
    }

    @Test
    fun `chapter count buckets`() {
        assertTrue(matches("very_long", snapshot(totalChapterNum = 1000)))
        assertTrue(matches("long", snapshot(totalChapterNum = 500)))
        assertTrue(matches("long", snapshot(totalChapterNum = 999)))
        assertTrue(matches("medium", snapshot(totalChapterNum = 200)))
        assertTrue(matches("medium", snapshot(totalChapterNum = 499)))
        assertTrue(matches("short", snapshot(totalChapterNum = 49)))
        assertFalse(matches("short", snapshot(totalChapterNum = 50)))
        assertFalse(matches("medium", snapshot(totalChapterNum = 500)))
        assertFalse(matches("very_long", snapshot(totalChapterNum = 999)))
    }

    @Test
    fun `update status rules`() {
        assertTrue(matches("has_update", snapshot(lastCheckCount = 1)))
        assertFalse(matches("has_update", snapshot(lastCheckCount = 0)))
        assertTrue(matches("cannot_update", snapshot(canUpdate = false)))
        assertFalse(matches("cannot_update", snapshot(canUpdate = true)))
    }

    @Test
    fun `matchingNames only keeps rules hitting at least one book`() {
        val rules = listOf(
            SmartTag.ResolvedRule("audio", "有声", "音频书籍") { matches("audio", it) },
            SmartTag.ResolvedRule("video", "视频", "视频书籍") { matches("video", it) },
        )
        val snapshots = listOf(
            snapshot(type = BookType.text),
            snapshot(type = BookType.audio),
        )
        assertEquals(listOf("有声"), SmartTag.matchingNames(snapshots, rules))
    }
}
