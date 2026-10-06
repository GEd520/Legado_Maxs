package io.legado.app.ui.video

import androidx.compose.runtime.Composable
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.components.dialog.AppCacheRangeContent
import io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment

/**
 * 视频章节缓存范围弹窗（Compose）。
 *
 * 默认从当前播放章节缓存到最后一章，可手动改起止章节。
 * 真正的缓存动作交给调用方（Activity）执行：弹窗一确认就会 dismiss，
 * 在弹窗自己的生命周期里发协程会被立刻取消。
 */
class VideoCacheRangeDialog(
    private val onConfirmRange: (start: Int, end: Int) -> Unit
) : BaseComposeDialogFragment() {

    @Composable
    override fun DialogContent() {
        val book = VideoPlay.book ?: return
        val total = book.totalChapterNum.takeIf { it > 0 } ?: VideoPlay.toc?.size ?: 0
        AppCacheRangeContent(
            bookName = book.name,
            totalChapterNum = total,
            defaultStart = (book.durChapterIndex + 1).coerceAtLeast(1),
            onDismiss = { dismiss() },
            onConfirm = { start, end ->
                dismiss()
                onConfirmRange(start, end)
            }
        )
    }
}
