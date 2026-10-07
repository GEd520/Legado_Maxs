package io.legado.app.ui.book.storage

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存管理页的列表筛选与章节筛选逻辑（testing.md §16）
 *
 * 归类/清点数据来自数据库与文件系统，这里只覆盖能在内存里判定的纯逻辑；
 * 界面状态机与缓存任务的启动交给 Activity 侧的 Fake [CacheTaskStarter] 走真机验证。
 */
class CacheManageViewModelTest {

    private fun book(
        name: String = "",
        author: String = "",
        originName: String = "",
        bookUrl: String = "https://test.local/book"
    ) = Book(bookUrl = bookUrl, name = name, author = author, originName = originName)

    private fun item(
        name: String = "",
        author: String = "",
        originName: String = "",
        bookUrl: String = "https://test.local/book"
    ) = CacheBookItem(
        book = book(name, author, originName, bookUrl),
        mode = CacheManageMode.BOOK,
        cachedCount = 0,
        totalChapterCount = 0
    )

    @Test
    fun `列表搜索命中书名作者与书源且忽略大小写`() {
        val items = listOf(
            item(name = "斗破苍穹", author = "天蚕土豆", originName = "书源甲"),
            item(name = "Big Novel", author = "someone", originName = "source b")
        )

        assertEquals(listOf("斗破苍穹"), items.filter { it.matchesKey("斗破") }.map { it.book.name })
        assertEquals(listOf("斗破苍穹"), items.filter { it.matchesKey("天蚕") }.map { it.book.name })
        assertEquals(listOf("斗破苍穹"), items.filter { it.matchesKey("书源甲") }.map { it.book.name })
        //忽略大小写
        assertEquals(listOf("Big Novel"), items.filter { it.matchesKey("big novel") }.map { it.book.name })
        assertEquals(listOf("Big Novel"), items.filter { it.matchesKey("SOURCE") }.map { it.book.name })
        assertTrue(items.filter { it.matchesKey("不存在") }.isEmpty())
    }

    @Test
    fun `章节搜索空关键字返回全部并按标题忽略大小写`() {
        val chapters = listOf(
            chapter("第1章 开端"),
            chapter("第2章 Chapter Two")
        )

        assertEquals(2, chapters.filterByKey("").size)
        assertEquals(1, chapters.filterByKey("开端").size)
        assertEquals(1, chapters.filterByKey("chapter two").size)
        assertTrue(chapters.filterByKey("尾声").isEmpty())
    }

    @Test
    fun `章节三态筛选按已缓存标记分开`() {
        val chapters = listOf(
            CacheChapterItem(chapter("第1章"), cached = true),
            CacheChapterItem(chapter("第2章"), cached = false),
            CacheChapterItem(chapter("第3章"), cached = true)
        )

        assertEquals(3, chapters.applyChapterFilter(CacheChapterFilter.ALL).size)
        assertEquals(
            listOf("第1章", "第3章"),
            chapters.applyChapterFilter(CacheChapterFilter.CACHED).map { it.chapter.title }
        )
        assertEquals(
            listOf("第2章"),
            chapters.applyChapterFilter(CacheChapterFilter.UNCACHED).map { it.chapter.title }
        )
    }

    @Test
    fun `章节索引合并为连续区间`() {
        assertTrue(emptyList<Int>().toRanges().isEmpty())
        assertEquals(listOf(3 to 3), listOf(3).toRanges())
        assertEquals(listOf(0 to 2), listOf(0, 1, 2).toRanges())
        assertEquals(listOf(0 to 1, 3 to 4, 9 to 9), listOf(0, 1, 3, 4, 9).toRanges())
    }

    @Test
    fun `分类对应书类型且音视频按媒体缓存判定`() {
        assertEquals(BookType.text, CacheManageMode.BOOK.bookType)
        assertEquals(BookType.audio, CacheManageMode.AUDIO.bookType)
        assertEquals(BookType.video, CacheManageMode.VIDEO.bookType)
        assertEquals(BookType.image, CacheManageMode.MANGA.bookType)

        assertTrue(CacheManageMode.AUDIO.isMedia)
        assertTrue(CacheManageMode.VIDEO.isMedia)
        assertFalse(CacheManageMode.BOOK.isMedia)
        assertFalse(CacheManageMode.MANGA.isMedia)
    }

    private fun chapter(title: String = "", index: Int = 0) = BookChapter(
        url = "https://test.local/book/$index",
        title = title,
        index = index
    )
}
