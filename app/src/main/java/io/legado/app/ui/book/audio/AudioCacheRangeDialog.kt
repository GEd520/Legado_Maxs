package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable
import io.legado.app.model.AudioPlay
import io.legado.app.ui.widget.components.dialog.AppCacheRangeContent
import io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment

/**
 * 音频章节缓存范围弹窗（Compose）。
 *
 * 默认从当前播放章节缓存到最后一章，可手动改起止章节。
 * 真正的缓存动作交给调用方（Activity）执行：弹窗一确认就会 dismiss，
 * 在弹窗自己的生命周期里发协程会被立刻取消。
 */
class AudioCacheRangeDialog(
    private val onConfirmRange: (start: Int, end: Int) -> Unit
) : BaseComposeDialogFragment() {

    @Composable
    override fun DialogContent() {
        val book = AudioPlay.book ?: return
        // 缓存走真实章节表，不用模拟章节数
        val total = AudioPlay.chapterSize.takeIf { it > 0 } ?: book.totalChapterNum
        AppCacheRangeContent(
            bookName = book.name,
            totalChapterNum = total,
            defaultStart = (AudioPlay.durChapterIndex + 1).coerceAtLeast(1),
            onDismiss = { dismiss() },
            onConfirm = { start, end ->
                dismiss()
                onConfirmRange(start, end)
            }
        )
    }
}
