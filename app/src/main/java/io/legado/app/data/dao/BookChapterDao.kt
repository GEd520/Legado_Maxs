package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookChapter

/**
 * 书章节数据访问接口
 */
@Dao
interface BookChapterDao {

    @Query("SELECT * FROM chapters where bookUrl = :bookUrl and title like '%'||:key||'%' order by `index`")
    fun search(bookUrl: String, key: String): List<BookChapter>

    @Query("SELECT * FROM chapters where bookUrl = :bookUrl and `index` >= :start and `index` <= :end and title like '%'||:key||'%' order by `index`")
    fun search(bookUrl: String, key: String, start: Int, end: Int): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl order by `index`")
    fun getChapterList(bookUrl: String): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl and `index` >= :start and `index` <= :end order by `index`")
    fun getChapterList(bookUrl: String, start: Int, end: Int): List<BookChapter>

    @Query("select * from chapters where bookUrl = :bookUrl and `index` = :index")
    fun getChapter(bookUrl: String, index: Int): BookChapter?

    @Query("select * from chapters where bookUrl = :bookUrl and `title` = :title")
    fun getChapter(bookUrl: String, title: String): BookChapter?

    @Query("select count(url) from chapters where bookUrl = :bookUrl")
    fun getChapterCount(bookUrl: String): Int

    @Query("select bookUrl, count(url) as count from chapters where isVolume = 0 group by bookUrl")
    suspend fun getChapterCounts(): List<BookChapterCount>

    /**
     * 单本的真实章节数（不含卷标题）
     *
     * 与 [getChapterCounts] 同口径：列表里的"已缓存 x/y"两侧都不该把卷标题算进去
     */
    @Query("select count(url) from chapters where bookUrl = :bookUrl and isVolume = 0")
    fun getChapterCountWithoutVolume(bookUrl: String): Int

    /** 每本书的章节数（一次查全，避免逐本读章节表） */
    data class BookChapterCount(val bookUrl: String, val count: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookChapter: BookChapter)

    @Update
    fun update(vararg bookChapter: BookChapter)

    @Query("delete from chapters where bookUrl = :bookUrl")
    fun delByBook(bookUrl: String)

    @Query("update chapters set wordCount = :wordCount where bookUrl = :bookUrl and url = :url")
    fun upWordCount(bookUrl: String, url: String, wordCount: String)

    /** 视频书源解析出的真实媒体地址，离线缓存的判定与播放都依赖它 */
    @Query("update chapters set resourceUrl = :resourceUrl where bookUrl = :bookUrl and url = :url")
    fun upResourceUrl(bookUrl: String, url: String, resourceUrl: String)

}