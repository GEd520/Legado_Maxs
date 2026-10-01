package io.legado.app.ui.book.storage.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composeActionShape
import io.legado.app.ui.widget.components.navigationBarBottomInset

/**
 * 缓存列表底部的批量操作栏（上传全部 / 删除全部）
 *
 * 底部要在导航条之上；两项等宽平分
 */
@Composable
fun CacheBatchBar(
    enabled: Boolean,
    onUploadAll: () -> Unit,
    onDeleteAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppDimens.screenPadding,
                end = AppDimens.screenPadding,
                bottom = AppDimens.screenPadding + navigationBarBottomInset
            )
    ) {
        BatchActionButton(
            text = stringResource(R.string.cache_manage_upload_all),
            onClick = onUploadAll,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(AppDimens.manageActionSpacing))
        BatchActionButton(
            text = stringResource(R.string.cache_manage_delete_all),
            onClick = onDeleteAll,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 整行铺满的批量按钮（与卡片上的操作按钮同款胶囊，仅宽度策略不同）
 */
@Composable
fun BatchActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = composeActionShape(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.manageActionHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = AppDimens.DISABLED_CONTENT_ALPHA
                    )
                },
                maxLines = 1
            )
        }
    }
}
