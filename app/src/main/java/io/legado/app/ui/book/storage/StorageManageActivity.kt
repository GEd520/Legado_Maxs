package io.legado.app.ui.book.storage

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi

/**
 * 缓存管理页（入口：我的 - 精准管理 - 存储管理）
 *
 * 页面有两个数据源：本 Activity 的两个 ViewModel——按书的缓存管理（[CacheManageViewModel]）
 * 与按缓存类型清点的统计（[StorageManageViewModel]）。
 */
class StorageManageActivity : BaseComposeActivity() {

    private val viewModel: StorageManageViewModel by viewModels {
        StorageManageViewModel.Factory
    }

    private val cacheViewModel: CacheManageViewModel by viewModels {
        CacheManageViewModel.Factory(cacheTaskStarter())
    }

    /**
     * 缓存任务在后台服务里跑，界面靠事件跟着刷新，否则缓存下完列表还停在"已缓存 0/1"
     */
    override fun observeLiveBus() {
        //两个事件都订阅：进度事件带书地址，状态事件有时不带
        observeEvent<String>(EventBus.UP_DOWNLOAD) {
            if (it.isNotBlank()) cacheViewModel.refreshItem(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD_STATE) {
            if (it.isNotBlank()) cacheViewModel.refreshItem(it)
        }
    }

    /**
     * 缓存任务由 CacheBookService 执行，这里只把"缓存哪些章节"翻译成 CacheBook 的区间调用
     * （连续章节合并成一个区间，避免逐章启动服务）
     */
    private fun cacheTaskStarter() = CacheTaskStarter { book, chapters ->
        val indexes = chapters.asSequence()
            .filterNot { it.isVolume }
            .map { it.index }
            .distinct()
            .sorted()
            .toList()
        if (indexes.isEmpty() || book.isLocal) {
            0
        } else {
            indexes.toRanges().forEach { (start, end) ->
                CacheBook.start(this, book, start, end)
            }
            indexes.size
        }
    }

    @Composable
    override fun ComposeContent() {
        CacheManageScreen(
            cacheViewModel = cacheViewModel,
            storageViewModel = viewModel,
            onBackClick = { finish() },
            onOpenPath = { path ->
                startActivity<FileManageActivity> {
                    putExtra(FileManageActivity.EXTRA_INITIAL_PATH, path)
                }
            },
            onEvent = ::handleEvent
        )
    }

    private fun handleEvent(event: CacheManageEvent) {
        when (event) {
            is CacheManageEvent.Toast -> {
                if (event.args.isEmpty()) {
                    toastOnUi(event.resId)
                } else {
                    toastOnUi(getString(event.resId, *event.args.toTypedArray()))
                }
            }

            is CacheManageEvent.OpenChapter -> startActivityForBook(event.book)
        }
    }
}

/**
 * 把有序的章节索引合并为连续区间 [start, end]
 */
private fun List<Int>.toRanges(): List<Pair<Int, Int>> {
    if (isEmpty()) return emptyList()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var start = this[0]
    var previous = this[0]
    for (index in drop(1)) {
        if (index == previous + 1) {
            previous = index
        } else {
            ranges.add(start to previous)
            start = index
            previous = index
        }
    }
    ranges.add(start to previous)
    return ranges
}
