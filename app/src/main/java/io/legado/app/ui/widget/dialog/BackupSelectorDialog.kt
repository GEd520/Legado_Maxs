package io.legado.app.ui.widget.dialog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.help.storage.BackupSelectorConfig
import io.legado.app.ui.config.widget.SegmentedTabRow
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment
import io.legado.app.ui.widget.components.dialog.MultiSelectDialogContent

/**
 * 备份选择器弹窗。
 *
 * 这个文件只负责 Compose 弹窗展示和用户事件转发，具体加载、选择和保存逻辑交给 ViewModel。
 *
 * 弹窗内按备份目标分成「本地备份」与「WebDAV 云备份」两个选项卡，
 * 两者各自维护独立的勾选结果，互不干扰，切换选项卡不会丢失改动。
 * 备份目标本身（本地备份目录、WebDAV 服务器）仍由备份与恢复页面统一设置。
 */
class BackupSelectorDialog : BaseComposeDialogFragment() {

    private val viewModel by viewModels<BackupSelectorViewModel>()

    @Composable
    override fun DialogContent() {
        BackupSelectorDialogContent(
            viewModel = viewModel,
            onDismiss = { dismiss() }
        )
    }
}

@Composable
fun BackupSelectorDialogContent(
    viewModel: BackupSelectorViewModel,
    onDismiss: () -> Unit
) {
    // 保持 Composable 只负责渲染；加载、选择和持久化都放在 ViewModel 中处理。
    val uiState by viewModel.uiState.collectAsState()

    when (val uiStateValue = uiState) {
        BackupSelectorUiState.Loading -> {
            BackupSelectorLoadingDialog(onDismiss = onDismiss)
        }

        is BackupSelectorUiState.Content -> {
            MultiSelectDialogContent(
                title = stringResource(R.string.backup_selector),
                groups = uiStateValue.groups,
                selectedKeys = uiStateValue.selectedKeys,
                totalSizeCalculator = viewModel::formatTotalSize,
                onSelectionChange = viewModel::onSelectionChange,
                onDismiss = {
                    viewModel.saveSelection()
                    onDismiss()
                },
                onSelectAll = viewModel::selectAll,
                onDeselectAll = viewModel::deselectAll,
                headerContent = {
                    // 本地备份与 WebDAV 云备份各自维护一份勾选结果，切换只改展示目标
                    SegmentedTabRow(
                        tabs = BackupSelectorConfig.Scope.values().toList(),
                        progress = if (uiStateValue.scope == BackupSelectorConfig.Scope.WebDav) {
                            1f
                        } else {
                            0f
                        },
                        onTabClick = viewModel::onScopeChange,
                        labelText = { stringResource(it.labelRes()) }
                    )
                }
            )
        }
    }
}

@StringRes
private fun BackupSelectorConfig.Scope.labelRes(): Int = when (this) {
    BackupSelectorConfig.Scope.Local -> R.string.backup_scope_local
    BackupSelectorConfig.Scope.WebDav -> R.string.backup_scope_webdav
}

@Composable
private fun BackupSelectorLoadingDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = pageCardContainerColor()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
