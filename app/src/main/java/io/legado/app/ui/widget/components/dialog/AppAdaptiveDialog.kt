package io.legado.app.ui.widget.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 通用 Compose 弹窗，外观与 Material3 的 AlertDialog 对齐（书架「智能标签管理」原本就是那个外观）：
 * extraLarge 圆角、surfaceContainerHigh 底色、24dp 内边距、headlineSmall 标题、按钮右对齐。
 *
 * 窗口用平台默认的对话框宽度（本机实测 320dp / 88.9% 屏宽，居中），面板宽度在 280~560dp 之间自适应；
 * 高度按内容自适应、最高不超过窗口的 [maxHeightFraction]。**不要**改成 usePlatformDefaultWidth = false：
 * 那会变成整屏宽，与其它弹窗对不齐。
 *
 * 内容超高时由调用方在 [content] 里自行滚动（LazyColumn / verticalScroll）。
 * 宿主可以是 Compose 界面，也可以是 [BaseComposeDialogFragment]（它自己带一层透明窗口，
 * 真正的弹窗窗口由本组件提供，项目里的 TextMenuConfigDialog 也是这个用法）。
 *
 * @param onDismiss 点外部/返回键时回调
 * @param description 标题下的一行说明，可空
 * @param buttons 底部按钮行（用 TextButton，右对齐），可空
 */
@Composable
fun AppAdaptiveDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    minWidth: Dp = 280.dp,
    maxWidth: Dp = 560.dp,
    maxHeightFraction: Float = 0.9f,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties()
    ) {
        AppAdaptiveDialogPanel(
            title = title,
            modifier = modifier,
            description = description,
            minWidth = minWidth,
            maxWidth = maxWidth,
            maxHeightFraction = maxHeightFraction,
            buttons = buttons,
            content = content
        )
    }
}

/**
 * [AppAdaptiveDialog] 的面板部分（不含 Dialog 窗口）
 */
@Composable
private fun AppAdaptiveDialogPanel(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    minWidth: Dp = 280.dp,
    maxWidth: Dp = 560.dp,
    maxHeightFraction: Float = 0.9f,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .sizeIn(minWidth = minWidth, maxWidth = maxWidth)
                .wrapContentHeight()
                .heightIn(max = maxHeight * maxHeightFraction),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    modifier = Modifier.padding(bottom = 16.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        modifier = Modifier.padding(bottom = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                //fill = false：内容少时面板跟着收起来，内容多时最多占满剩余空间（由调用方滚动）
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(bottom = 24.dp)
                ) {
                    content()
                }
                if (buttons != null) {
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        buttons()
                    }
                }
            }
        }
    }
}
