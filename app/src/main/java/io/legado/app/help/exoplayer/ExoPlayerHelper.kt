package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import okhttp3.CacheControl
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit


@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    /** 播放缓存（非离线）上限，沿用历史行为 */
    private const val PLAYBACK_CACHE_MAX_BYTES = 100L * 1024 * 1024

    /** 单本书视频离线缓存上限，超出后按 LRU 淘汰 */
    private const val VIDEO_CACHE_MAX_BYTES = 4L * 1024 * 1024 * 1024

    /** 每本书的视频离线缓存目录，放在书缓存目录内，随书缓存一起删除 */
    private const val VIDEO_BOOK_CACHE_DIR = "video_media"

    /** 下载完成后落一个标记，用于判定自适应流（m3u8/mpd）是否已完整缓存 */
    private const val VIDEO_COMPLETE_SUFFIX = "_complete"

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        val mediaItemBuilder = MediaItem.Builder().setUri(formatUrl)
        return mediaItemBuilder.build()
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()
        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            ).setDataSourceFactory(resolvingDataSource)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }


    private val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        ResolvingDataSource.Factory(cacheDataSourceFactory) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(Uri.parse(url))
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                } catch (_: Exception) {
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory（全局播放缓存）
     */
    val cacheDataSourceFactory by lazy {
        val playbackCache = simpleCache(legacyCacheDir, PLAYBACK_CACHE_MAX_BYTES)
        //使用自定义的CacheDataSource以支持设置UA
        CacheDataSource.Factory()
            .setCache(playbackCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(playbackCache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    /**
     * Okhttp DataSource.Factory
     */
    private val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    private fun okhttpDataFactory(headers: Map<String, String>): OkHttpDataSource.Factory {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        return OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
            .setDefaultRequestProperties(headers)
    }

    private val databaseProvider: StandaloneDatabaseProvider by lazy {
        StandaloneDatabaseProvider(appCtx)
    }

    /** media3 同一目录只允许一个 SimpleCache 实例，这里按目录复用 */
    private val cacheMap = ConcurrentHashMap<String, Cache>()
    private val cacheLock = Any()

    private fun simpleCache(dir: File, maxBytes: Long): Cache {
        val path = dir.absolutePath
        cacheMap[path]?.let { return it }
        return synchronized(cacheLock) {
            cacheMap[path] ?: SimpleCache(
                dir.apply { mkdirs() },
                LeastRecentlyUsedCacheEvictor(maxBytes),
                databaseProvider
            ).also { cacheMap[path] = it }
        }
    }

    private val legacyCacheDir: File
        get() = File(appCtx.externalCache, "exoplayer")

    /**
     * 每本书的视频离线缓存目录（book_cache/<书>/video_media）
     */
    fun videoBookCacheDir(book: Book): File {
        return File(BookHelp.getCacheDir(book), VIDEO_BOOK_CACHE_DIR)
    }

    /**
     * 释放指定缓存目录的实例，删除缓存目录前必须调用，否则 media3 会持有目录锁
     */
    private fun releaseCache(cacheDir: File) {
        synchronized(cacheLock) {
            cacheMap.remove(cacheDir.absolutePath)?.release()
        }
    }

    fun releaseBookCaches(book: Book) {
        releaseCache(videoBookCacheDir(book))
    }

    /**
     * 按书籍缓存目录释放视频缓存实例（删除/移动目录前调用）
     * @param bookCacheDir book_cache/<书籍文件夹名>
     */
    fun releaseVideoCacheOf(bookCacheDir: File) {
        releaseCache(File(bookCacheDir, VIDEO_BOOK_CACHE_DIR))
    }

    fun releaseAllBookCaches() {
        synchronized(cacheLock) {
            val iterator = cacheMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.key.startsWith(legacyCacheDir.absolutePath)) {
                    entry.value.release()
                    iterator.remove()
                }
            }
        }
    }

    /**
     * 视频章节内容 -> 可下载的媒体地址与请求头
     * 视频书源的正文规则返回的就是媒体地址，这里与播放链路保持一致的解析方式
     */
    suspend fun resolveMediaRequest(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter
    ): MediaRequest {
        val content = WebBook.getContentAwait(bookSource, book, chapter).trim()
        if (content.isEmpty()) {
            throw NoStackTraceException("正文为空，无法缓存")
        }
        if (content.startsWith("<")) {
            // 正文返回 mpd 文本的场景，播放时会落临时文件，离线缓存暂不支持
            throw NoStackTraceException("该视频为 mpd 文本，暂不支持离线缓存")
        }
        val analyzeUrl = AnalyzeUrl(content, source = bookSource, ruleData = book, chapter = chapter)
        return MediaRequest(analyzeUrl.url, analyzeUrl.headerMap)
    }

    /**
     * 把一章的媒体文件完整下载进缓存目录
     * @return 本次实际下载到的字节数
     */
    fun cacheMedia(
        request: MediaRequest,
        book: Book,
        progress: ((bytesCached: Long, newBytesCached: Long) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null
    ): Long {
        val urls = getMediaUrls(request.url)
        require(urls.isNotEmpty()) { "媒体地址为空" }
        val cacheDir = videoBookCacheDir(book)
        var totalCached = 0L
        urls.forEach { url ->
            require(isDownloadableMediaUrl(url)) { "不支持下载的媒体地址: $url" }
            if (shouldCancel?.invoke() == true) {
                throw kotlinx.coroutines.CancellationException("video cache cancelled")
            }
            var cached = 0L
            val downloader = DefaultDownloaderFactory(
                videoCacheDataSourceFactory(request.headers, cacheDir, writable = true),
                Executor { it.run() }
            ).createDownloader(
                DownloadRequest.Builder(MD5Utils.md5Encode(url), url.toUri())
                    .setMimeType(guessMediaMimeType(url))
                    .build()
            )
            downloader.download { _, bytesCached, _ ->
                if (shouldCancel?.invoke() == true) {
                    downloader.cancel()
                    throw kotlinx.coroutines.CancellationException("video cache cancelled")
                }
                val newBytesCached = (bytesCached - cached).coerceAtLeast(0L)
                cached = bytesCached
                progress?.invoke(bytesCached, newBytesCached)
            }
            markMediaComplete(url, cacheDir)
            totalCached += cached
        }
        return totalCached
    }

    /**
     * 视频播放用的 MediaSource
     *
     * 读取 cacheDir 指向的书级缓存目录：已离线缓存的章节直接走本地文件，
     * 未缓存的章节边播边写入同一个目录
     * @param cacheDir 为空时使用全局播放缓存目录
     */
    fun createVideoMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        cacheDir: File? = null,
        mimeType: String? = null,
        writable: Boolean = true
    ): MediaSource {
        return DefaultMediaSourceFactory(videoPlaybackDataSourceFactory(headers, cacheDir, writable))
            .setLiveTargetOffsetMs(5000)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(mimeType ?: guessMediaMimeType(url))
                    .build()
            )
    }

    /**
     * 播放器扩展名 -> MIME，地址本身看不出流类型时（如 m3u8 只出现在 query 里）使用
     */
    fun mimeTypeOfExtension(extension: String?): String? {
        return when (extension?.lowercase()) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            "ism" -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    /**
     * 判定该章节的媒体文件是否已经完整缓存
     */
    fun isVideoCached(url: String?, book: Book): Boolean {
        if (url.isNullOrBlank()) return false
        val cacheDir = videoBookCacheDir(book)
        if (!cacheDir.exists()) return false
        val urls = getMediaUrls(url)
        if (urls.isEmpty()) return false
        if (urls.any { !isDownloadableMediaUrl(it) }) return false
        val cache = simpleCache(cacheDir, VIDEO_CACHE_MAX_BYTES)
        return urls.all { isVideoUrlCached(cache, it, cacheDir) }
    }

    /**
     * 缓存被 LRU 淘汰后完成标记可能残留，所以判定时都要求缓存里确实还有数据
     */
    private fun isVideoUrlCached(cache: Cache, url: String, cacheDir: File): Boolean {
        val cachedBytes = cache.getCachedBytes(url, 0, Long.MAX_VALUE)
        if (cachedBytes <= 0) return false
        if (isAdaptiveMediaUrl(url)) {
            //自适应流的切片按切片地址单独缓存，只能靠完成标记判定
            return completeMarker(url, cacheDir).isFile
        }
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return if (contentLength > 0) {
            cache.isCached(url, 0, contentLength)
        } else {
            completeMarker(url, cacheDir).isFile
        }
    }

    /**
     * 播放数据源：始终优先读缓存目录（离线缓存的章节才能离线播放），
     * [writable] 为 false 时不写缓存，此时若缓存目录都还不存在就直接走网络，
     * 避免"只是看视频"也在书籍缓存目录里凭空建出空目录
     */
    private fun videoPlaybackDataSourceFactory(
        headers: Map<String, String>,
        cacheDir: File? = null,
        writable: Boolean = true
    ): DataSource.Factory {
        val targetCacheDir = cacheDir ?: legacyCacheDir
        if (!writable && !targetCacheDir.exists()) {
            return okhttpDataFactory(headers)
        }
        return videoCacheDataSourceFactory(headers, targetCacheDir, writable)
    }

    /**
     * 走缓存目录的数据源，[writable] 为 false 时是只读缓存
     */
    private fun videoCacheDataSourceFactory(
        headers: Map<String, String>,
        cacheDir: File,
        writable: Boolean
    ): CacheDataSource.Factory {
        //非书级的旧播放缓存沿用原来的 100MB 上限
        val maxBytes = if (cacheDir == legacyCacheDir) {
            PLAYBACK_CACHE_MAX_BYTES
        } else {
            VIDEO_CACHE_MAX_BYTES
        }
        val videoCache = simpleCache(cacheDir, maxBytes)
        return CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(okhttpDataFactory(headers))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .apply {
                if (writable) {
                    setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory()
                            .setCache(videoCache)
                            .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
                    )
                }
            }
    }

    private fun markMediaComplete(url: String, cacheDir: File) {
        runCatching { completeMarker(url, cacheDir).createNewFile() }
    }

    private fun completeMarker(url: String, cacheDir: File): File {
        val dir = File(cacheDir.parentFile, cacheDir.name + VIDEO_COMPLETE_SUFFIX).apply { mkdirs() }
        return File(dir, MD5Utils.md5Encode(url))
    }

    private fun getMediaUrls(url: String): List<String> {
        if (url.isJsonArray()) {
            GSON.fromJsonArray<String>(url).getOrNull()?.filter { it.isNotBlank() }?.let {
                return it
            }
        }
        return listOf(url)
    }

    private fun isDownloadableMediaUrl(url: String): Boolean {
        val scheme = url.toUri().scheme ?: return false
        return scheme.equals("http", true) ||
            scheme.equals("https", true) ||
            (scheme.equals("file", true) && isAdaptiveMediaUrl(url))
    }

    private fun isAdaptiveMediaUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ism")
    }

    private fun guessMediaMimeType(url: String): String? {
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".ism") || lower.endsWith(".isml") -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    data class MediaRequest(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    fun getMediaSource(context: Context, url: String): MediaSource? {
        val uris = GSON.fromJsonArray<String>(url).getOrNull() ?: return null
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)
        val mediaSourceBuilder = ConcatenatingMediaSource2.Builder()
        for (uri in uris) {
            mediaSourceBuilder.add(
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri)), 3000
            )
        }
        return mediaSourceBuilder.build()
    }
}
