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
            //本轮要覆盖的那份清单就是旧清单，用它补回"缓存时用的那个地址"
            val oldManifest = read(book)
            write(book, chapters) { chapter ->
                when {
                    book.isLocal -> false
                    book.isAudio || book.isVideo -> cachedMediaUrl(book, chapter, oldManifest) != null
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

    /**
     * 该章节"确实还有缓存"的媒体地址
     *
     * 缓存是按**缓存当时的地址**做 key 的，而章节表里的 `resourceUrl` 之后可能被新解析结果覆盖、
     * 或者地址本身带了会过期的时间签名；清单里留着缓存时用的那个地址。
     * 两个都试一遍，只要有一个在缓存里，这章就还能离线播——否则会出现
     * "缓存明明在磁盘上，却因为地址变了读不到、点使用缓存也救不回来"。
     *
     * @param manifest 已知清单时传入，避免逐章重复读文件
     */
    fun cachedMediaUrl(
        book: Book,
        chapter: BookChapter,
        manifest: CacheBookManifest? = read(book)
    ): String? {
        if (!book.isAudio && !book.isVideo) return null
        val recorded = manifest?.let { manifestMediaUrl(it, chapter) }
        return sequenceOf(chapter.resourceUrl, recorded)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .firstOrNull { isMediaCached(it, book) }
    }

    /** 清单里记录的该章媒体地址（缓存时缓存用的那个） */
    fun manifestMediaUrl(manifest: CacheBookManifest, chapter: BookChapter): String? {
        val recorded = manifest.chapters.firstOrNull { it.index == chapter.index }
            ?: manifest.chapters.firstOrNull { it.url == chapter.url }
            ?: return null
        return (recorded.resourceUrl ?: recorded.url).takeIf { it.isNotBlank() }
    }

    /** 地址在该书的媒体缓存里是否完整可用 */
    fun isMediaCached(url: String, book: Book): Boolean {
        return when {
            book.isVideo -> ExoPlayerHelper.isVideoCached(url, book)
            book.isAudio -> ExoPlayerHelper.isMediaCached(url, book)
            else -> false
        }
    }

    /** 清单里记录的该章（缓存当时的序号与标题） */
    fun manifestChapter(manifest: CacheBookManifest?, chapter: BookChapter): CacheChapterManifest? {
        if (manifest == null) return null
        return manifest.chapters.firstOrNull { it.index == chapter.index }
            ?: manifest.chapters.firstOrNull { it.url == chapter.url }
    }

    /** 用清单记录的信息拼一个最小章节对象（只用于按清单取文件名 / 判定缓存） */
    fun toChapter(recorded: CacheChapterManifest, bookUrl: String): BookChapter {
        return BookChapter(
            url = recorded.url,
            title = recorded.title,
            bookUrl = bookUrl,
            index = recorded.index
        )
    }

    /**
     * 文本/漫画章节已缓存时对应的文件名
     *
     * 文本缓存的文件名 = 章节序号 + 标题 md5，所以"目录刷新改了标题""源在中间插了章节让序号整体偏移"
     * 之后，按当前章节算出的名字就对不上磁盘上的文件了——缓存明明还在，却判定成没缓存、还会重下。
     * 清单里记着缓存当时的序号与标题，用它再算一遍就能找回来。
     *
     * @param cacheNames 该书的缓存文件集合，批量判定时传入可避免逐章列目录
     */
    fun cachedTextFileName(
        book: Book,
        chapter: BookChapter,
        manifest: CacheBookManifest? = read(book),
        cacheNames: Set<String>? = null
    ): String? {
        val names = cacheNames ?: BookHelp.getCacheDir(book).list()?.toSet().orEmpty()
        chapter.getFileName().takeIf { names.contains(it) }?.let { return it }
        val recorded = manifestChapter(manifest, chapter)
            ?.takeIf { it.cached }
            ?: return null
        return toChapter(recorded, chapter.bookUrl).getFileName().takeIf { names.contains(it) }
    }

    /** 文本/漫画章节已缓存的正文文件 */
    fun cachedTextFile(
        book: Book,
        chapter: BookChapter,
        manifest: CacheBookManifest? = read(book)
    ): File? {
        val dir = BookHelp.getCacheDir(book)
        val name = cachedTextFileName(book, chapter, manifest) ?: return null
        return File(dir, name)
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
        book: Book,
        chapters: List<BookChapter>,
        manifest: CacheBookManifest?
    ): Boolean {
        if (manifest == null) return false
        var changed = false
        chapters.forEach { chapter ->
            val recorded = manifestMediaUrl(manifest, chapter) ?: return@forEach
            if (chapter.resourceUrl == recorded) return@forEach
            val currentCached = chapter.resourceUrl?.let { isMediaCached(it, book) } == true
            //当前地址还能读到缓存就别动；读不到而清单里的地址有缓存，就换回缓存时那个地址
            if (currentCached) return@forEach
            chapter.resourceUrl = recorded
            changed = true
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

    /** 已缓存章节的序号集合：列表计数只核对这几章，不必逐章查缓存 */
    val cachedIndexes: Set<Int>
        get() = chapters.asSequence().filter { it.cached }.mapTo(hashSetOf()) { it.index }
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
