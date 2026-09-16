package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [BookTagManagement] 单元测试。
 */
class BookTagManagementTest {

    @Test
    fun `mergeTags combines and deduplicates`() {
        assertEquals(
            listOf("玄幻", "修仙", "都市"),
            BookTagManagement.mergeTags(listOf("玄幻", "修仙"), listOf("修仙", "都市")),
        )
    }

    @Test
    fun `mergeTags configured takes priority`() {
        val result = BookTagManagement.mergeTags(listOf("Tag"), listOf("tag"))
        assertEquals(listOf("Tag"), result)
    }

    @Test
    fun `mergeTags empty inputs returns empty`() {
        assertEquals(emptyList<String>(), BookTagManagement.mergeTags(emptyList(), emptyList()))
    }

    @Test
    fun `mergeTags filters blank entries`() {
        assertEquals(
            listOf("玄幻"),
            BookTagManagement.mergeTags(listOf("玄幻", "  ", ""), listOf("")),
        )
    }

    @Test
    fun `reusableTags excludes current tags`() {
        val all = listOf("玄幻", "修仙", "都市", "科幻")
        val current = listOf("玄幻")
        assertEquals(listOf("修仙", "都市", "科幻"), BookTagManagement.reusableTags(current, all))
    }

    @Test
    fun `reusableTags case insensitive exclusion`() {
        val all = listOf("Tag", "Other")
        val current = listOf("tag")
        assertEquals(listOf("Other"), BookTagManagement.reusableTags(current, all))
    }

    @Test
    fun `renameInGroups renames old tag in every group`() {
        val groups = mapOf(1L to listOf("玄幻", "都市"), 2L to listOf("玄幻"))
        val renamed = BookTagManagement.renameInGroups(groups, "玄幻", "仙侠")
        assertEquals(listOf("仙侠", "都市"), renamed[1L])
        assertEquals(listOf("仙侠"), renamed[2L])
    }

    @Test
    fun `renameInGroups dedupes when new name already exists`() {
        val groups = mapOf(1L to listOf("仙侠", "玄幻"))
        assertEquals(listOf("仙侠"), BookTagManagement.renameInGroups(groups, "玄幻", "仙侠")[1L])
    }

    @Test
    fun `renameInGroups keeps map when old tag absent`() {
        val groups = mapOf(1L to listOf("都市"))
        assertSame(groups, BookTagManagement.renameInGroups(groups, "玄幻", "仙侠"))
    }

    @Test
    fun `renameInHiddenGroups renames old tag`() {
        val groups = mapOf(1L to setOf("玄幻", "都市"))
        assertEquals(
            setOf("仙侠", "都市"),
            BookTagManagement.renameInHiddenGroups(groups, "玄幻", "仙侠")[1L],
        )
    }

    @Test
    fun `pruneUnknownGroups drops configs of deleted groups`() {
        val tags = mapOf(1L to listOf("玄幻"), 2L to listOf("修仙"))
        assertEquals(
            mapOf(1L to listOf("玄幻")),
            BookTagManagement.pruneUnknownGroups(tags, setOf(1L, -1L)),
        )
    }

    @Test
    fun `pruneUnknownGroups keeps map when all groups valid`() {
        val tags = mapOf(1L to listOf("玄幻"))
        assertSame(tags, BookTagManagement.pruneUnknownGroups(tags, setOf(1L)))
    }

    @Test
    fun `tagBarLabel appends book count`() {
        assertEquals("玄幻·3", BookTagManagement.tagBarLabel("玄幻", "全部", 3))
        assertEquals("本地·0", BookTagManagement.tagBarLabel("本地", "全部", 0))
    }

    @Test
    fun `tagBarLabel uses all text for blank tag`() {
        assertEquals("全部·10", BookTagManagement.tagBarLabel("", "全部", 10))
    }

    @Test
    fun `updateTag add new tag`() {
        val write = BookTagManagement.updateTag("玄幻", "修仙", selected = true)
        assertEquals("玄幻,修仙", write?.customTag)
    }

    @Test
    fun `updateTag add to empty`() {
        val write = BookTagManagement.updateTag(null, "玄幻", selected = true)
        assertEquals("玄幻", write?.customTag)
    }

    @Test
    fun `updateTag remove existing tag`() {
        val write = BookTagManagement.updateTag("玄幻,修仙", "修仙", selected = false)
        assertEquals("玄幻", write?.customTag)
    }

    @Test
    fun `updateTag remove last tag returns null customTag`() {
        val write = BookTagManagement.updateTag("玄幻", "玄幻", selected = false)
        assertNull(write?.customTag)
    }

    @Test
    fun `updateTag no change returns null`() {
        val write = BookTagManagement.updateTag("玄幻,修仙", "修仙", selected = true)
        assertNull(write)
    }

    @Test
    fun `updateTag add duplicate returns null`() {
        val write = BookTagManagement.updateTag("玄幻", "玄幻", selected = true)
        assertNull(write)
    }

    @Test
    fun `updateTag case insensitive add`() {
        val write = BookTagManagement.updateTag("Tag", "tag", selected = true)
        // Tag already present (case insensitive), no change
        assertNull(write)
    }

    @Test
    fun `updateTag case insensitive remove`() {
        val write = BookTagManagement.updateTag("Tag,Other", "tag", selected = false)
        assertEquals("Other", write?.customTag)
    }
}
