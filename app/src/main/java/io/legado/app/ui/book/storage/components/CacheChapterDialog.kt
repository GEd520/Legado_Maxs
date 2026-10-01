package io.legado.app.ui.book.storage.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import io.legado.app.R
import io.legado.app.ui.book.storage.CacheChapterDialogState
import io.legado.app.ui.book.storage.CacheChapterFilter
import io.legado.app.ui.book.storage.CacheChapterItem
import io.legado.app.ui.config.widget.SegmentedTabRow
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composePanelShape

/**
 * 章节缓存弹窗
 *
 * 与参考分支一致的能力：全部/已缓存/未缓存 三态筛选、搜索、长按多选后批量缓存或删除，
 * 单击章节则直接用缓存打开该章（阅读/播放），这是"直接选择使用缓存"的核心交互
 */
@Composable
fun CacheChapterDialog(
    state: CacheChapterDialogState,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onFilterChange: (CacheChapterFilter) -> Unit,
    onChapterClick: (CacheChapterItem) -> Unit,
    onChapterLongClick: (CacheChapterItem) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCacheSelected: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(DIALOG_HEIGHT_FRACTION),
            shape = composePanelShape(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.cache_manage_chapters),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        start = AppDimens.screenPadding,
                        top = AppDimens.screenPadding,
                        end = AppDimens.screenPadding
                    )
                )
                OutlinedTextField(
                    value = state.key,
                    onValueChange = onSearch,
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    placeholder = { Text(stringResource(R.string.cache_manage_search_chapter)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AppDimens.screenPadding,
                            top = AppDimens.manageRowSpacing,
                            end = AppDimens.screenPadding
                        )
                )
                val filters = listOf(
                    CacheChapterFilter.ALL,
                    CacheChapterFilter.CACHED,
                    CacheChapterFilter.UNCACHED
                )
                SegmentedTabRow(
                    tabs = filters,
                    progress = filters.indexOf(state.filter).toFloat() / (filters.size - 1),
                    onTabClick = onFilterChange,
                    modifier = Modifier.padding(
                        start = AppDimens.screenPadding,
                        top = AppDimens.manageRowSpacing,
                        end = AppDimens.screenPadding
                    ),
                    labelText = { filter ->
                        stringResource(
                            when (filter) {
                                CacheChapterFilter.ALL -> R.string.cache_manage_all_chapters
                                CacheChapterFilter.CACHED -> R.string.cache_manage_cached
                                CacheChapterFilter.UNCACHED -> R.string.cache_manage_not_cached
                            }
                        )
                    }
                )
                if (!state.selectionMode) {
                    Text(
                        text = stringResource(R.string.cache_manage_long_press_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = AppDimens.screenPadding,
                            top = AppDimens.manageRowSpacing,
                            end = AppDimens.screenPadding,
                            bottom = AppDimens.manageRowSpacing
                        )
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.chapters, key = { it.chapter.index }) { item ->
                            ChapterRow(
                                item = item,
                                selected = state.selectedIndexes.contains(item.chapter.index),
                                selectionMode = state.selectionMode,
                                onClick = { onChapterClick(item) },
                                onLongClick = { onChapterLongClick(item) }
                            )
                        }
                    }
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (state.chapters.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chapter_list_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                if (state.selectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppDimens.screenPadding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cache_manage_selected_count,
                                state.selectedCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        ManageActionButton(
                            text = stringResource(R.string.select_all),
                            onClick = onSelectAll
                        )
                        Spacer(Modifier.width(AppDimens.manageActionSpacing))
                        ManageActionButton(
                            text = stringResource(R.string.cache_manage_cache_selected),
                            onClick = onCacheSelected,
                            enabled = state.selectedCount > 0
                        )
                        Spacer(Modifier.width(AppDimens.manageActionSpacing))
                        ManageActionButton(
                            text = stringResource(R.string.delete),
                            onClick = onDeleteSelected,
                            enabled = state.selectedCount > 0
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AppDimens.screenPadding,
                            bottom = AppDimens.screenPadding
                        )
                ) {
                    if (state.selectionMode) {
                        ManageActionButton(
                            text = stringResource(R.string.cancel),
                            onClick = onClearSelection
                        )
                        Spacer(Modifier.width(AppDimens.manageActionSpacing))
                    }
                    ManageActionButton(
                        text = stringResource(R.string.close),
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    item: CacheChapterItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.manageChapterRowHeight)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                horizontal = AppDimens.screenPadding,
                vertical = AppDimens.manageRowSpacing
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(AppDimens.manageRowSpacing))
        }
        Text(
            text = item.chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(AppDimens.manageRowSpacing))
        Text(
            text = stringResource(
                if (item.cached) R.string.cache_manage_cached else R.string.cache_manage_not_cached
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (item.cached) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private const val DIALOG_HEIGHT_FRACTION = 0.85f
