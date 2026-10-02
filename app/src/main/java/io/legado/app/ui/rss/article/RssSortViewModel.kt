package io.legado.app.ui.rss.article

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.removeSortCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS 分类/排序页 ViewModel
 *
 * 数据访问经 [RssSortRepository] 构造注入（默认 Default 实现），
 * 数据操作默认走 [ioDispatcher]（同 BaseViewModel.execute：IO 执行、回调回 Main），
 * JVM 单测可注入共享测试调度器（testing.md §16）
 */
class RssSortViewModel(
    private val repository: RssSortRepository = RssSortRepository.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    var url: String? = null
    var sortUrl: String? = null
    var rssSource: RssSource? = null
    var order = System.currentTimeMillis()
    val articleStyle get() = rssSource?.articleStyle
    var searchKey: String? = null
    var sourceName: String? = null

    fun initData(intent: Intent, onFinally: () -> Unit) {
        initData(intent.getStringExtra("sourceUrl"), onFinally)
        // Intent 入口可能携带指定分类与搜索词
        sortUrl = intent.getStringExtra("sortUrl") ?: sortUrl
        searchKey = intent.getStringExtra("key")
    }

    /**
     * 无 Intent 入口（新版订阅主界面按源切换时使用）：
     * 只重置源相关状态，不携带指定分类与搜索词。
     */
    fun initData(sourceUrl: String?, onFinally: () -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            try {
                url = sourceUrl
                url?.let { key ->
                    rssSource = repository.getSourceByKey(key)
                    rssSource?.let {
                        sourceName = it.sourceName
                    } ?: run {
                        rssSource = RssSource(sourceUrl = key)
                    }
                }
                sortUrl = null
                searchKey = null
            } finally {
                withContext(Dispatchers.Main) { onFinally() }
            }
        }
    }

    fun switchLayout() {
        val source = rssSource ?: return
        viewModelScope.launch(ioDispatcher) {
            if (source.articleStyle < 4) {
                source.articleStyle += 1
            } else {
                source.articleStyle = 0
            }
            repository.updateSource(source)
        }
    }

    fun clearArticles() {
        viewModelScope.launch(ioDispatcher) {
            url?.let { repository.deleteArticles(it) }
            order = System.currentTimeMillis()
        }
    }

    fun clearSortCache(onFinally: () -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            try {
                rssSource?.removeSortCache()
            } finally {
                withContext(Dispatchers.Main) { onFinally() }
            }
        }
    }

    fun getRecords(origin: String? = null): List<RssReadRecord> = repository.getRecords(origin)

    fun countRecords(origin: String? = null): Int = repository.countRecords(origin)

    fun deleteAllRecord(origin: String? = null) {
        viewModelScope.launch(ioDispatcher) {
            repository.deleteRecords(origin)
        }
    }
}