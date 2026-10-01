package io.legado.app.ui.main.rss.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.AppImage

/**
 * 订阅页的订阅源单元：图标 + 名称，点击打开订阅源，长按弹操作菜单。
 */
@Composable
internal fun RssSourceGridItem(
    modifier: Modifier = Modifier,
    sourceItem: RssSourceItem,
    actions: RssSourceActions,
) {
    var menuExpanded by remember(sourceItem.sourceUrl) { mutableStateOf(false) }
    Box(modifier = modifier) {
        RssGridCell(
            name = sourceItem.sourceName,
            onClick = { actions.onOpen(sourceItem) },
            onLongClick = { menuExpanded = true },
            icon = {
                AppImage(
                    modifier = Modifier.size(AppDimens.rssGridIconSize),
                    model = sourceItem.sourceIcon,
                    contentDescription = null,
                    placeholderRes = R.drawable.image_rss,
                    errorRes = R.drawable.image_rss,
                    sourceOrigin = sourceItem.sourceUrl,
                    cornerRadius = AppDimens.rssGridIconCornerRadius,
                )
            },
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            RssSourceMenuAction.entries.forEach { action ->
                if (action == RssSourceMenuAction.Login && !sourceItem.hasLoginUrl) {
                    return@forEach
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(action.titleRes()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        actions.onMenuAction(sourceItem, action)
                    },
                )
            }
        }
    }
}

/** 菜单文案复用 View 版菜单（[R.menu.rss_main_item]）的同一批字符串资源 */
private fun RssSourceMenuAction.titleRes(): Int {
    return when (this) {
        RssSourceMenuAction.Edit -> R.string.edit
        RssSourceMenuAction.ToTop -> R.string.to_top
        RssSourceMenuAction.Login -> R.string.login
        RssSourceMenuAction.Disable -> R.string.disable_source
        RssSourceMenuAction.Delete -> R.string.delete
    }
}
