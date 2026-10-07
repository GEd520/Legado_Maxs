package io.legado.app.help.exoplayer

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [mediaMimeTypeOfUrl] / [mediaExtensionOfUrl] 单元测试。
 *
 * 这个判定是"下载用什么下载器 / 缓存算不算完整 / 播放按哪种流播"三处共用的口径：
 * 只按路径扩展名判断会让代理式地址走上普通文件下载器，缓存里只留下一个播放列表，
 * 而播放侧按 HLS 去读它，于是出现"显示已缓存、点使用缓存却放不出来"。
 */
class MediaMimeTypeOfUrlTest {

    @Test
    fun `路径结尾是 m3u8 时按自适应流处理`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mediaMimeTypeOfUrl("https://cdn.example.com/hls/index.m3u8"),
        )
    }

    @Test
    fun `代理式地址的路径结尾不是 m3u8 也按自适应流处理`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mediaMimeTypeOfUrl("https://api.nxvav.cn/api/m3u8/?url=%2Findex.m3u8"),
        )
    }

    @Test
    fun `m3u8 只出现在 query 里时同样按自适应流处理`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mediaMimeTypeOfUrl("https://api.example.com/player/get?id=1&type=m3u8"),
        )
    }

    @Test
    fun `mpd 与 ism 按各自的自适应流处理`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            mediaMimeTypeOfUrl("https://cdn.example.com/dash/manifest.mpd?token=abc"),
        )
        assertEquals(
            MimeTypes.APPLICATION_SS,
            mediaMimeTypeOfUrl("https://cdn.example.com/smooth/stream.ism/Manifest"),
        )
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mediaMimeTypeOfUrl("https://cdn.example.com/HLS/INDEX.M3U8"),
        )
    }

    @Test
    fun `普通文件地址返回空`() {
        assertNull(mediaMimeTypeOfUrl("https://cdn.example.com/video/38868090884-1-192.mp4?deadline=1"))
        assertNull(mediaMimeTypeOfUrl("https://api.example.com/player/get?id=1"))
        assertNull(mediaMimeTypeOfUrl(""))
    }

    @Test
    fun `流类型标记黏在别的单词里不算命中`() {
        assertNull(mediaMimeTypeOfUrl("https://cdn.example.com/prism/index.jpg"))
        assertNull(mediaMimeTypeOfUrl("https://cdn.example.com/optimism/cover.png?exif=1"))
    }

    @Test
    fun `overrideExtension 与 MIME 一一对应`() {
        assertEquals("m3u8", mediaExtensionOfUrl("https://api.nxvav.cn/api/m3u8/?url=x"))
        assertEquals("mpd", mediaExtensionOfUrl("https://cdn.example.com/manifest.mpd"))
        assertEquals("ism", mediaExtensionOfUrl("https://cdn.example.com/stream.ism/Manifest"))
        assertNull(mediaExtensionOfUrl("https://cdn.example.com/video.mp4"))
    }
}
