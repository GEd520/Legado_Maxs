package io.legado.app.help

import androidx.collection.LruCache

/**
 * 自由比例封面「高/宽比」的进程级缓存（新版发现 / 经典发现 / 新版订阅的瀑布流共用）。
 *
 * 瀑布流条目的高度取决于图片的真实宽高比。比例若只存在组合状态里（`remember`），条目滑出
 * 视口被回收后重新滑回来时会先用默认比例占位、等图片加载完再改成真实比例；高度一变，
 * 瀑布流会重排整列（其后的条目联动位移），表现就是「往下滑一段再往上滑，书籍会跳动」。
 * 缓存下来后，重新进入视口的初始比例即为真实值，高度不再变化。
 *
 * 键沿用 View 版 `RssArticlesAdapter3` 的口径（图片地址），两者互不影响、也不共享磁盘缓存：
 * 这里只做进程内缓存，避免在主线程读盘。
 *
 * [androidx.collection.LruCache] 自身线程安全（内部同步），可跨线程读写。
 */
object CoverAspectRatioCache {

    /** 覆盖常见瀑布流一屏条目量级；超出按 LRU 淘汰，命中率足够 */
    private const val MAX_ENTRIES = 512

    private val ratios = LruCache<String, Float>(MAX_ENTRIES)

    /** 取「高/宽比」；未缓存或键为空时返回 0f（调用方按"未知"处理） */
    fun get(key: String?): Float {
        if (key.isNullOrBlank()) return 0f
        return ratios[key] ?: 0f
    }

    /** 记录「高/宽比」；非正数/非有限值忽略 */
    fun put(key: String?, ratio: Float) {
        if (key.isNullOrBlank()) return
        if (ratio <= 0f || ratio.isNaN() || ratio.isInfinite()) return
        ratios.put(key, ratio)
    }
}
