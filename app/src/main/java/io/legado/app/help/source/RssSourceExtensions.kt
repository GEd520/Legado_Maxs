package io.legado.app.help.source

import io.legado.app.data.entities.RssSource
import io.legado.app.utils.ACache
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import com.script.rhino.runScriptWithContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val aCache by lazy { ACache.get("rssSortUrl") }

private fun RssSource.getSortUrlsKey(): String {
    return MD5Utils.md5Encode(sourceUrl + sortUrl)
}

suspend fun RssSource.sortUrls(): List<Pair<String, String>> {
    return arrayListOf<Pair<String, String>>().apply {
        val sortUrlsKey = getSortUrlsKey()
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                var str = sortUrl
                if (sortUrl?.startsWith("<js>", false) == true
                    || sortUrl?.startsWith("@js:", false) == true
                ) {
                    str = aCache.getAsString(sortUrlsKey)
                    if (str.isNullOrBlank()) {
                        val jsStr = if (sortUrl!!.startsWith("@")) {
                            sortUrl!!.substring(4)
                        } else {
                            sortUrl!!.substring(4, sortUrl!!.lastIndexOf("<"))
                        }
                        str = runScriptWithContext {
                            evalJS(jsStr).toString()
                        }
                        aCache.put(sortUrlsKey, str)
                    }
                }
                str?.split("(&&|\n)+".toRegex())?.forEach { sort ->
                    val name = sort.substringBefore("::")
                    val url = sort.substringAfter("::", "")
                    if (url.isNotEmpty()) {
                        add(Pair(name, url))
                    }
                }
                if (isEmpty()) {
                    add(Pair("", sourceUrl))
                }
            }
        }
    }
}

/**
 * 这类源只能按网页打开，解析不出分类与文章列表：
 * `singleUrl` 的源本身就是"一个网址"，`ruleArticles` 为空的源则没有列表规则可解析。
 * 新版订阅里命中这个判据时不下发分类/文章请求，改走"打开源"入口
 * （对齐参考分支 RssFragment.opensInWebPopup）。
 */
fun RssSource.opensInWebPopup(): Boolean = singleUrl || ruleArticles.isNullOrBlank()

suspend fun RssSource.removeSortCache() {
    withContext(Dispatchers.IO) {
        aCache.remove(getSortUrlsKey())
    }
}
