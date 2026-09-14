package io.legado.app.ui.main.bookshelf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.help.book.BookTagManagement

/**
 * 标签管理 Screen 的回调集合，打包传递以避免参数过多。
 */
internal data class BookshelfTagManageCallbacks(
    val onBack: () -> Unit,
    val onShowAddTagDialog: (Long, String) -> Unit,
    val onAddTags: (Long, List<String>) -> Unit,
    val onTagVisibilityChange: (Long, String, Boolean) -> Unit,
    val onManageBooks: (BookshelfTagGroupUi, String) -> Unit,
    val onRequestDelete: (BookshelfTagGroupUi, String) -> Unit,
    val onConfirmDelete: (Long, String, String, List<BookTagInfo>) -> Unit,
    val onDismissDialog: () -> Unit,
    val onSaveAssignment: (BookTagAssignmentUi, Set<String>) -> Unit,
    val onRequestRename: (BookshelfTagGroupUi, String) -> Unit,
    val onRenameTag: (Long, String, String, String) -> Unit,
    val onReorderTags: (Long, List<String>) -> Unit,
    val onShowSmartTagDialog: () -> Unit,
    val onSmartTagsEnabledChange: (Boolean) -> Unit,
    val onSmartTagEnabledChange: (String, Boolean) -> Unit,
)

/**
 * 标签管理主 Screen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfTagManageScreen(
    state: BookshelfTagManageUiState,
    callbacks: BookshelfTagManageCallbacks,
    modifier: Modifier = Modifier,
) {
    // 用不可能是真实分组 ID 的值表示"尚未初始化"。
    // 不能用 -1L，因为那恰好是 BookGroup.IdAll，会导致初始状态被误判为已选中"全部"分组。
    var selectedGroupId by rememberSaveable {
        mutableLongStateOf(Long.MIN_VALUE)
    }
    // focusGroupId 由 Activity 传入，首次加载数据后才有有效值；
    // 仅在尚未选择任何分组时使用它作为初始选中项。
    LaunchedEffect(state.focusGroupId, state.groups) {
        if (state.groups.isNotEmpty() &&
            state.groups.none { it.groupId == selectedGroupId }
        ) {
            // 优先使用传入的 focusGroupId，不存在时回退到第一个分组
            selectedGroupId = state.groups.firstOrNull { it.groupId == state.focusGroupId }?.groupId
                ?: state.groups.firstOrNull()?.groupId ?: -1L
        }
    }
    val selectedGroup = state.groups.firstOrNull { it.groupId == selectedGroupId }

    BackHandler(enabled = state.dialog != null) { callbacks.onDismissDialog() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bookshelf_tag_manage),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.smart_tag_manage)) },
                                onClick = {
                                    menuExpanded = false
                                    callbacks.onShowSmartTagDialog()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.groups.isNotEmpty()) {
                GroupSelector(
                    groups = state.groups,
                    selectedGroupId = selectedGroupId,
                    onSelect = { selectedGroupId = it },
                )
            }
            when {
                state.loading -> LoadingContent()
                selectedGroup == null -> EmptyContent()
                else -> TagGroupContent(
                    group = selectedGroup,
                    onAddTags = { callbacks.onShowAddTagDialog(selectedGroup.groupId, selectedGroup.groupName) },
                    onTagVisibilityChange = { tag, visible ->
                        callbacks.onTagVisibilityChange(selectedGroup.groupId, tag, visible)
                    },
                    onManageBooks = { tag -> callbacks.onManageBooks(selectedGroup, tag) },
                    onDeleteTag = { tag -> callbacks.onRequestDelete(selectedGroup, tag) },
                    onRenameTag = { tag -> callbacks.onRequestRename(selectedGroup, tag) },
                    onReorderTags = { newOrder -> callbacks.onReorderTags(selectedGroup.groupId, newOrder) },
                )
            }
        }
    }

    val dialog = state.dialog
    when (dialog) {
        is BookshelfTagDialogState.AddTags -> {
            val group = state.groups.firstOrNull { it.groupId == dialog.groupId }
            if (group != null) {
                val allTags = remember(state.groups) {
                    state.groups.flatMap { it.tags.map { item -> item.name } }
                }
                val reusableTags = remember(group.tags, allTags) {
                    BookTagManagement.reusableTags(
                        current = group.tags.map { it.name },
                        all = allTags,
                    )
                }
                BookTagAddDialog(
                    group = group,
                    reusableTags = reusableTags,
                    onDismiss = callbacks.onDismissDialog,
                    onAdd = { tags ->
                        callbacks.onDismissDialog()
                        callbacks.onAddTags(group.groupId, tags)
                    },
                )
            }
        }
        is BookshelfTagDialogState.ManageBooks -> {
            BookTagAssignmentDialog(
                assignment = dialog.assignment,
                onDismiss = callbacks.onDismissDialog,
                onSave = { selected ->
                    callbacks.onSaveAssignment(dialog.assignment, selected)
                },
            )
        }
        is BookshelfTagDialogState.DeleteConfirm -> {
            AlertDialog(
                onDismissRequest = callbacks.onDismissDialog,
                title = { Text(stringResource(R.string.bookshelf_tag_delete_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.bookshelf_tag_delete_message,
                            dialog.tag,
                            dialog.groupName,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            callbacks.onConfirmDelete(
                                dialog.groupId,
                                dialog.groupName,
                                dialog.tag,
                                dialog.books,
                            )
                        },
                    ) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = callbacks.onDismissDialog) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        is BookshelfTagDialogState.RenameTag -> {
            BookTagRenameDialog(
                groupId = dialog.groupId,
                groupName = dialog.groupName,
                oldTag = dialog.oldTag,
                onDismiss = callbacks.onDismissDialog,
                onRename = { newTag ->
                    callbacks.onDismissDialog()
                    callbacks.onRenameTag(dialog.groupId, dialog.groupName, dialog.oldTag, newTag)
                },
            )
        }
        is BookshelfTagDialogState.SmartTags -> {
            SmartTagManageDialog(
                enabled = state.smartTagsEnabled,
                tags = state.smartTags,
                onEnabledChange = callbacks.onSmartTagsEnabledChange,
                onTagEnabledChange = callbacks.onSmartTagEnabledChange,
                onDismiss = callbacks.onDismissDialog,
            )
        }
        null -> Unit
    }
}

/**
 * 智能标签管理对话框。
 *
 * 总开关关闭时子标签置灰不可切换（子标签只能在智能标签开启时启用）。
 */
@Composable
private fun SmartTagManageDialog(
    enabled: Boolean,
    tags: List<SmartTagItemUi>,
    onEnabledChange: (Boolean) -> Unit,
    onTagEnabledChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smart_tag_manage)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.smart_tag_enable),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.smart_tag_enable_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.smart_tag_sub_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.smart_tag_sub_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tags.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.smart_tag_book_count,
                                    tag.assignedCount,
                                ) + " · " + tag.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = tag.enabled,
                            enabled = enabled,
                            onCheckedChange = { onTagEnabledChange(tag.id, it) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.bookshelf_tag_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
