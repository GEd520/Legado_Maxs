package io.legado.app.ui.book.storage

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.CacheBookManifest

/**
 * 缓存管理的书目分类：书籍 / 音频 / 视频 / 漫画（与参考分支的四个 Tab 一致）
 */
enum class CacheManageMode(
    @StringRes val titleRes: Int,
    @BookType.Type val bookType: Int
) {
    BOOK(R.string.cache_manage_books, BookType.text),
    AUDIO(R.string.cache_manage_audio, BookType.audio),
    VIDEO(R.string.cache_manage_video, BookType.video),
    MANGA(R.string.cache_manage_manga, BookType.image);

    /** 音视频的缓存内容是媒体文件，已缓存判定与统计走 ExoPlayer 媒体缓存 */
    val isMedia: Boolean
        get() = this == AUDIO || this == VIDEO
}

/** 章节弹窗的筛选条件 */
enum class CacheChapterFilter { ALL, CACHED, UNCACHED }

/**
 * 一本书在当前分类下的缓存概况
 * @param totalChapterCount 0 表示章节列表未知（目录尚未加载过）
 * @param inBookshelf 书是否还在书架；不在书架但缓存还在时，卡片提供"加入书架"
 */
data class CacheBookItem(
    val book: Book,
    val mode: CacheManageMode,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val storageSizeBytes: Long = 0L,
    val storageCalculated: Boolean = false,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true
)

/** 章节弹窗里的一项 */
data class CacheChapterItem(
    val chapter: BookChapter,
    val cached: Boolean
)

/**
 * 章节弹窗状态（Compose 规范 §4.5：弹窗由 UiState 条件渲染）
 */
data class CacheChapterDialogState(
    val book: Book,
    val key: String = "",
    val filter: CacheChapterFilter = CacheChapterFilter.ALL,
    val chapters: List<CacheChapterItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selectedIndexes: Set<Int> = emptySet(),
    val selectionMode: Boolean = false
) {
    val selectedCount: Int
        get() = selectedIndexes.size
}

/** 需要二次确认的操作 */
sealed interface CacheManageConfirm {
    data class DeleteChapters(val book: Book, val chapters: List<BookChapter>) : CacheManageConfirm
    data class DeleteBook(val book: Book) : CacheManageConfirm
    data class DeleteAll(val books: List<Book>) : CacheManageConfirm
}

/** 页面 UiState */
data class CacheManageUiState(
    val mode: CacheManageMode = CacheManageMode.BOOK,
    val loading: Boolean = false,
    /** 当前分类下**过滤后**的列表（过滤在内存里做，输入时不重新查库/扫盘） */
    val items: List<CacheBookItem> = emptyList(),
    /** 当前分类的搜索关键字 */
    val searchKey: String = "",
    val searching: Boolean = false,
    val chapterDialog: CacheChapterDialogState? = null,
    val confirm: CacheManageConfirm? = null,
    val working: Boolean = false,
    val error: String? = null
)

/** 一次性事件：提示与"用缓存打开某章"（导航由 Activity 执行） */
sealed interface CacheManageEvent {
    data class Toast(@StringRes val resId: Int, val args: List<Any> = emptyList()) : CacheManageEvent
    data class OpenChapter(val book: Book, val chapter: BookChapter) : CacheManageEvent
}

/**
 * 启动缓存任务的入口
 *
 * ViewModel 不持有 Context（Compose 规范 §4.1），由 Activity 注入实现并转发给 CacheBook，
 * 这样 ViewModel 可单测（传 Fake 即可断言"缓存了哪些章节"）
 */
fun interface CacheTaskStarter {
    /** @return 实际加入缓存队列的章节数 */
    fun start(book: Book, chapters: List<BookChapter>): Int
}
