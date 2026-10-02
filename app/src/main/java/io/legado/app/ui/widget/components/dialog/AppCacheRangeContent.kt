package io.legado.app.ui.widget.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 起止章节输入：两个数字输入框，范围不合法时禁用「开始下载」。
 * 供音频/视频播放页的章节缓存范围弹窗共用。
 */
@Composable
fun AppCacheRangeContent(
    bookName: String,
    totalChapterNum: Int,
    defaultStart: Int,
    onDismiss: () -> Unit,
    onConfirm: (start: Int, end: Int) -> Unit
) {
    val total = totalChapterNum.coerceAtLeast(1)
    var startText by remember { mutableStateOf(defaultStart.coerceIn(1, total).toString()) }
    var endText by remember { mutableStateOf(total.toString()) }
    val start = startText.toIntOrNull()
    val end = endText.toIntOrNull()
    val startValid = start != null && start in 1..total
    val endValid = end != null && end in 1..total && start != null && end >= start

    AppAdaptiveDialog(
        title = stringResource(R.string.offline_cache),
        onDismiss = onDismiss,
        description = bookName,
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(
                onClick = { if (start != null && end != null) onConfirm(start, end) },
                enabled = startValid && endValid
            ) {
                Text(stringResource(R.string.download_start))
            }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.start_chapter)) },
                singleLine = true,
                isError = !startValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.to),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.end)) },
                singleLine = true,
                isError = !endValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.chapter) + " 1 ~ " + total,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
