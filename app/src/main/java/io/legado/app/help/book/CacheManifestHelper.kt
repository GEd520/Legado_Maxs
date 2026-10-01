package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.globalExecutor
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File

/**
 * 书籍缓存清单（book_cache/<书>/cache_manifest.json）
 *
 * 存在的意义：缓存目录里的文件无法自证"属于哪本书、哪一章"。清单把书籍信息与章节列表
 * 落在缓存目录内，于是：
 * - 书已从书架删除、只剩缓存时，缓存管理页仍能列出书名/章节数，并支持"加回书架/使用缓存"；
 * - 目录刷新把章节行的 resourceUrl 清掉后，可用清单里记录的历史地址把它补回来，
 *   避免已缓存的音视频变成无法播放的孤儿缓存。
 *
 * 清单只在"章节列表变化、缓存完成、缓存被删"等时机刷新（见各调用点），
 * 不在每次保存正文时写，避免大书目录频繁序列化。
 */
object CacheManifestHelper {

    const val MANIFEST_FILE_NAME = "cache_manifest.json"

    fun manifestFile(book: Book): File {
        return File(BookHelp.getCacheDir(book), MANIFEST_FILE_NAME)
    }

    fun hasManifest(cacheDir: File): Boolean {
        return File(cacheDir, MANIFEST_FILE_NAME).isFile
    }

    fun read(book: Book): CacheBookManifest? {
        return read(manifestFile(book))
    }

    fun read(file: File): CacheBookManifest? {
        if (!file.isFile) return null
        return runCatching {
            GSON.fromJsonObject<CacheBookManifest>(file.readText()).getOrNull()
        }.getOrNull()
    }

    fun listManifests(): List<CacheBookManifest> {
        return listManifests(listCacheDirs())
    }

    fun listCacheDirs(): List<File> {
        val root = File(BookHelp.cachePath)
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.toList()
            .orEmpty()
    }

    fun listManifests(cacheDirs: List<File>): List<CacheBookManifest> {
        return cacheDirs
            .asSequence()
            .mapNotNull { read(File(it, MANIFEST_FILE_NAME)) }
            .toList()
    }

    /**
     * 重写清单。[isChapterCached] 由调用方给出该章的缓存判定（文本/漫画看文件，音视频看媒体缓存）
     */
    fun write(
        book: Book,
        chapters: List<BookChapter>,
        isChapterCached: (BookChapter) -> Boolean
    ): CacheBookManifest? {
        val realChapters = chapters.filterNot { it.isVolume }
        val cachedByIndex = realChapters.associate { it.index to isChapterCached(it) }
        val cachedCount = cachedByIndex.values.count { it }
        val file = manifestFile(book)
        //没有已缓存章节时才删清单，且必须确认缓存目录真的空了：
        //目录里还有内容说明缓存仍在（只是文件名对不上章节，比如书名/章节变动过），
        //这份清单是"这本书还有缓存"的唯一凭据，删了就再也管不到它
        if (cachedCount <= 0 && !book.isAudio && !book.isVideo) {
            if (!file.parentFile.hasContent()) {
                file.delete()
            }
            return null
        }
        val cacheDir = file.parentFile ?: return null
        if (!cacheDir.exists()) {
            //缓存目录已经被清掉（用户清缓存）时不重建空目录
            if (cachedCount <= 0) return null
            cacheDir.mkdirs()
        }
        val manifest = CacheBookManifest(
            bookUrl = book.bookUrl,
            tocUrl = book.tocUrl,
            origin = book.origin,
            originName = book.originName,
            name = book.name,
            author = book.author,
            kind = book.kind,
            coverUrl = book.coverUrl,
            intro = book.intro,
            type = book.type,
            folderName = book.getFolderName(),
            latestChapterTitle = book.latestChapterTitle,
            totalChapterNum = realChapters.size.takeIf { it > 0 } ?: book.totalChapterNum,
            updatedAt = System.currentTimeMillis(),
            chapters = realChapters.map { chapter ->
                CacheChapterManifest(
                    index = chapter.index,
                    title = chapter.title,
                    url = chapter.url,
                    baseUrl = chapter.baseUrl,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl,
                    cached = cachedByIndex[chapter.index] == true
                )
            }
        )
        file.writeText(GSON.toJson(manifest))
        return manifest
    }

    fun refresh(
        book: Book,
        chapters: List<BookChapter> = appDb.bookChapterDao.getChapterList(book.bookUrl)
    ): CacheBookManifest? {
        return runCatching {
            if (chapters.isEmpty()) {
                //章节表里没有记录（多半是书已从书架删除、只剩缓存）：
                //缓存目录还在就保留清单，否则缓存管理页再也列不出这本书
                if (!BookHelp.getCacheDir(book).hasContent()) {
                    delete(book)
                }
                return@runCatching null
            }
            val cacheNames = if (book.isAudio || book.isVideo) {
                emptySet()
            } else {
                BookHelp.getCacheDir(book).list()?.toSet().orEmpty()
            }
            write(book, chapters) { chapter ->
                when {
                    book.isLocal -> false
                    book.isVideo -> ExoPlayerHelper.isVideoCached(chapter.resourceUrl, book)
                    book.isAudio -> ExoPlayerHelper.isMediaCached(chapter.resourceUrl, book)
                    //文本/漫画的缓存文件名 = 章节序号 + 标题 md5，只与章节自身有关
                    else -> cacheNames.contains(chapter.getFileName())
                }
            }
        }.onFailure {
            AppLog.put("刷新缓存清单失败 ${book.name}\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    /**
     * 后台刷新清单：调用点在章节列表/缓存状态变化处，不阻塞当前流程
     */
    fun refreshAsync(
        book: Book,
        chapters: List<BookChapter>? = null
    ) {
        globalExecutor.execute {
            if (chapters == null) {
                refresh(book)
            } else {
                refresh(book, chapters)
            }
        }
    }

    fun delete(book: Book) {
        manifestFile(book).delete()
    }

    fun delete(manifest: CacheBookManifest) {
        val folderName = manifest.folderName.takeIf { it.isNotBlank() }
            ?: toBook(manifest).getFolderName()
        File(File(BookHelp.cachePath), folderName)
            .resolve(MANIFEST_FILE_NAME)
            .delete()
    }

    /**
     * 清单 -> 书籍实体：书已不在书架时，缓存管理页靠它列出书名/作者/封面
     */
    fun toBook(manifest: CacheBookManifest): Book {
        return Book(
            bookUrl = manifest.bookUrl,
            tocUrl = manifest.tocUrl,
            origin = manifest.origin,
            originName = manifest.originName,
            name = manifest.name,
            author = manifest.author,
            kind = manifest.kind,
            coverUrl = manifest.coverUrl,
            intro = manifest.intro,
            type = manifest.type,
            latestChapterTitle = manifest.latestChapterTitle,
            totalChapterNum = manifest.totalChapterNum,
            canUpdate = false
        )
    }

    fun toChapters(
        manifest: CacheBookManifest,
        targetBookUrl: String = manifest.bookUrl
    ): List<BookChapter> {
        return manifest.chapters
            .sortedBy { it.index }
            .map { chapter ->
                BookChapter(
                    url = chapter.url,
                    title = chapter.title,
                    isVolume = false,
                    baseUrl = chapter.baseUrl,
                    bookUrl = targetBookUrl,
                    index = chapter.index,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl
                )
            }
    }

    /**
     * 目录刷新后章节行的 resourceUrl 会被新行覆盖为空，这里按章节序号从清单补回来
     * @return 是否有章节被补上地址
     */
    fun mergeResourceUrls(
        chapters: List<BookChapter>,
        manifest: CacheBookManifest?
    ): Boolean {
        if (manifest == null) return false
        val byIndex = manifest.chapters.associateBy { it.index }
        var changed = false
        chapters.forEach { chapter ->
            val resourceUrl = byIndex[chapter.index]?.resourceUrl
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            if (chapter.resourceUrl.isNullOrBlank()) {
                chapter.resourceUrl = resourceUrl
                changed = true
            }
        }
        return changed
    }
}

/**
 * 目录里除清单文件外是否还有缓存内容
 *
 * 只看"目录非空"会把清单自己算进去，导致清空缓存后清单永远删不掉
 */
private fun File?.hasContent(): Boolean {
    if (this == null || !isDirectory) return false
    return listFiles()?.any { it.name != CacheManifestHelper.MANIFEST_FILE_NAME } == true
}

data class CacheBookManifest(
    val version: Int = 1,
    val bookUrl: String = "",
    val tocUrl: String = "",
    val origin: String = "",
    val originName: String = "",
    val name: String = "",
    val author: String = "",
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val type: Int = 0,
    val folderName: String = "",
    val latestChapterTitle: String? = null,
    val totalChapterNum: Int = 0,
    val updatedAt: Long = 0L,
    val chapters: List<CacheChapterManifest> = emptyList()
) {
    val cachedChapterCount: Int
        get() = chapters.count { it.cached }
}

data class CacheChapterManifest(
    val index: Int = 0,
    val title: String = "",
    val url: String = "",
    val baseUrl: String = "",
    val isVip: Boolean = false,
    val isPay: Boolean = false,
    val resourceUrl: String? = null,
    val tag: String? = null,
    val wordCount: String? = null,
    val start: Long? = null,
    val end: Long? = null,
    val startFragmentId: String? = null,
    val endFragmentId: String? = null,
    val variable: String? = null,
    val imgUrl: String? = null,
    val cached: Boolean = false
)
