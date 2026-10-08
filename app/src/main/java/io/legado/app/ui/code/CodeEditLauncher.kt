package io.legado.app.ui.code

import android.content.Intent
import io.legado.app.help.CacheManager
import java.util.UUID

/**
 * CodeEditActivity 文本中转助手。
 *
 * 大文本若直接放进 Intent extra，以 UTF-16 打包出的 Binder 事务会超过约 500KB 的实际悬崖，
 * 系统抛 `TransactionTooLargeException` 并直接 SIGKILL 前台进程（打开大书源全屏编辑时
 * 白屏/黑屏重启的根因，与内存无关）。
 *
 * 这里对超过阈值的文本改走进程内 [CacheManager] 内存缓存中转，Intent 只传 key；
 * 小文本维持 `putExtra("text", …)` 直传。去程（启动编辑器）与回程（保存回传）复用同一套
 * key 规则，两端都由本类收口，避免只修去程留下回程半吊子。
 *
 * 注意：可编辑大文本走本类的 `textCacheKey`，与只读预览用的 `cacheKey`
 * （[io.legado.app.ui.widget.dialog.TextDialog]）区分开——后者会强制 `writable = false`。
 */
object CodeEditLauncher {

    /**
     * 超过该字符数改走内存中转。32k 字符 ≈ 64KB parcel，远离实测 Binder 事务悬崖
     * （480KB 过、523KB 死）。
     */
    private const val VIA_INTENT_MAX_CHARS = 32_000

    private const val CACHE_PREFIX = "codeEditText_"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_TEXT_CACHE_KEY = "textCacheKey"

    /**
     * 发送侧：把待编辑文本放进 Intent。
     * 超阈值时写入内存缓存并只传 key，否则维持 `putExtra("text", text)` 直传。
     * 启动编辑器的去程与 [CodeEditActivity] 保存回传的回程都调用本方法。
     */
    fun putText(intent: Intent, text: String) {
        if (text.length > VIA_INTENT_MAX_CHARS) {
            val key = CACHE_PREFIX + UUID.randomUUID()
            CacheManager.putMemory(key, text)
            intent.putExtra(EXTRA_TEXT_CACHE_KEY, key)
        } else {
            intent.putExtra(EXTRA_TEXT, text)
        }
    }

    /**
     * 接收侧：从 Intent 取回文本内容。
     * 兼容旧的 `putExtra("text", …)` 直传；命中大文本 key 时从内存缓存取出并读后即删，
     * 防止缓存滞留。去程加载与回程回传共用本方法。
     */
    fun readText(intent: Intent?): String? {
        intent?.getStringExtra(EXTRA_TEXT)?.let { return it }
        val key = intent?.getStringExtra(EXTRA_TEXT_CACHE_KEY) ?: return null
        return (CacheManager.getFromMemory(key) as? String).also {
            CacheManager.deleteMemory(key)
        }
    }

}
