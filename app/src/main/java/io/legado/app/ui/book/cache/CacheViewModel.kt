package io.legado.app.ui.book.cache

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isMedia
import io.legado.app.help.book.isVideo
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.utils.sendValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.collections.set


class CacheViewModel(application: Application) : BaseViewModel(application) {
    val upAdapterLiveData = MutableLiveData<String>()

    private var loadChapterCoroutine: Coroutine<Unit>? = null
    // 缓存每本书已缓存的章节URL集合（只在 Main 线程写入，界面在 Main 线程读）
    val cacheChapters = hashMapOf<String, HashSet<String>>()
    // 缓存每本书的缓存文件大小
    val cacheSizes = hashMapOf<String, Long>()
    private val bookRepository = BookRepository()
    
    // 用于检测是否是相同的书籍列表，避免重复加载
    private var lastLoadedBooksKey: String? = null
    // 正在重新计算的书籍，避免下载进度事件频繁触发重复计算
    private val refreshingBooks = hashSetOf<String>()
    // 刷新期间又收到刷新请求的书籍，结束后补刷一次
    private val pendingRefreshBooks = hashSetOf<String>()
    // 防止并发加载的标志
    private var isLoading = false

    /**
     * 加载书籍缓存文件信息
     * 优化点：
     * 1. 使用booksKey检测相同列表，避免重复计算
     * 2. 使用isLoading防止并发加载
     * 3. 每本书独立async并行：DB查询 + 文件扫描 + UI刷新，先完成的先显示
     * 4. 并行DB查询替代串行forEach
     */
    fun loadCacheFiles(books: List<Book>) {
        if (isLoading) return

        val booksKey = books.map { it.bookUrl }.sorted().joinToString(",")
        if (booksKey == lastLoadedBooksKey) return

        loadChapterCoroutine?.cancel()
        loadChapterCoroutine = execute {
            isLoading = true
            try {
                val newBooks = books.filter { !it.isLocal && !cacheChapters.contains(it.bookUrl) }
                if (newBooks.isEmpty()) {
                    lastLoadedBooksKey = booksKey
                    return@execute
                }

                // 每本书独立async并行处理，先完成的先刷新UI
                newBooks.map { book ->
                    async(Dispatchers.IO) {
                        try {
                            val (chapterCaches, cacheSize) = calcBookCache(book)
                            Triple(book.bookUrl, chapterCaches, cacheSize)
                        } catch (e: Exception) {
                            Triple(book.bookUrl, hashSetOf<String>(), 0L)
                        }
                    }
                }.awaitAll().forEach { (bookUrl, chapterCaches, cacheSize) ->
                    //结果统一回到 Main 线程写入，界面同时在读这两个 Map
                    withContext(Dispatchers.Main) {
                        cacheChapters[bookUrl] = chapterCaches
                        cacheSizes[bookUrl] = cacheSize
                    }
                    upAdapterLiveData.sendValue(bookUrl)
                }

                lastLoadedBooksKey = booksKey
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 计算单本书的已缓存章节与缓存目录占用
     */
    private suspend fun calcBookCache(book: Book): Pair<HashSet<String>, Long> {
        // 查询该书章节
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        // 扫描该书缓存文件
        val cacheNames = BookHelp.getCacheFiles(setOf(book.getFolderName()))[book.getFolderName()]
            ?: hashSetOf()
        // 匹配已缓存章节
        val chapterCaches = hashSetOf<String>()
        if (book.isMedia) {
            // 音视频章节的离线内容是媒体文件，按媒体缓存判定（地址可能变过，走可用缓存地址）
            book.totalChapterNum = chapters.size
            val manifest = CacheManifestHelper.read(book)
            chapters.forEach { chapter ->
                val cached = chapter.isVolume ||
                    CacheManifestHelper.cachedMediaUrl(book, chapter, manifest) != null
                if (cached) {
                    chapterCaches.add(chapter.url)
                }
            }
        } else if (cacheNames.isNotEmpty()) {
            book.totalChapterNum = chapters.size
            // 标题/序号被目录刷新改过时按当前名字找不到缓存文件，清单里记着缓存当时的名字
            val manifest = CacheManifestHelper.read(book)
            chapters.forEach { chapter ->
                val cached = chapter.isVolume ||
                    CacheManifestHelper.cachedTextFileName(book, chapter, manifest, cacheNames) != null
                if (cached) {
                    chapterCaches.add(chapter.url)
                }
            }
        }
        // 计算该书缓存文件夹大小
        val cacheSize = File(BookHelp.cachePath, book.getFolderName())
            .takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        return chapterCaches to cacheSize
    }

    /**
     * 重新计算单本书的已缓存章节与占用大小
     * 视频书缓存的是一章一个媒体文件，下载进度中需要按媒体缓存状态刷新
     */
    suspend fun refreshCache(book: Book) {
        synchronized(refreshingBooks) {
            if (!refreshingBooks.add(book.bookUrl)) {
                //刷新期间又来了事件，记下来补一次，避免界面停在中间值
                pendingRefreshBooks.add(book.bookUrl)
                return
            }
        }
        try {
            do {
                synchronized(pendingRefreshBooks) {
                    pendingRefreshBooks.remove(book.bookUrl)
                }
                val (chapterCaches, cacheSize) =
                    withContext(Dispatchers.IO) { calcBookCache(book) }
                //计算结果统一回到 Main 线程写入，界面同时在读这两个 Map
                withContext(Dispatchers.Main) {
                    cacheChapters[book.bookUrl] = chapterCaches
                    cacheSizes[book.bookUrl] = cacheSize
                }
            } while (synchronized(pendingRefreshBooks) {
                    pendingRefreshBooks.contains(book.bookUrl)
                })
        } finally {
            synchronized(refreshingBooks) {
                refreshingBooks.remove(book.bookUrl)
            }
        }
    }

    suspend fun getBookCover(bookName: String, bookAuthor: String): String? {
        return bookRepository.getBookCoverByNameAndAuthor(bookName, bookAuthor)
    }

    /**
     * 清理单本书的缓存状态
     * 清理后重置lastLoadedBooksKey，确保下次重新计算
     */
    fun clearCache(bookUrl: String) {
        cacheChapters[bookUrl] = hashSetOf()
        cacheSizes[bookUrl] = 0L
        lastLoadedBooksKey = null
    }

    /**
     * 清理所有缓存状态
     * 清理后重置lastLoadedBooksKey，确保下次重新计算
     */
    fun clearAllCache() {
        cacheChapters.clear()
        cacheSizes.clear()
        lastLoadedBooksKey = null
    }

}