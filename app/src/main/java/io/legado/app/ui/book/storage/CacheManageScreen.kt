package io.legado.app.ui.book.storage

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.ui.book.storage.components.CacheChapterDialog
import io.legado.app.ui.book.storage.components.CacheItemCard
import io.legado.app.ui.book.storage.components.CacheManageBookCard
import io.legado.app.ui.book.storage.components.CacheSummaryCard
import io.legado.app.ui.book.storage.components.ClearAllConfirmDialog
import io.legado.app.ui.book.storage.components.ClearConfirmDialog
import io.legado.app.ui.config.widget.SegmentedTabRow
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composeActionShape
import io.legado.app.ui.widget.components.AppPageTopBar
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.VerticalScrollbar
import io.legado.app.ui.widget.components.dialog.AppConfirmDialog
import io.legado.app.ui.widget.components.navigationBarBottomInset

/**
 * 缓存管理页
 *
 * 结构对齐参考分支的"缓存管理"：顶部五个分类（书籍/音频/视频/漫画/统计），
 * 前四类按书列出缓存并可对单章/整本做缓存、删除、上传、"使用缓存"，
 * 统计页沿用本项目原有的按缓存类型清点能力（[StorageManageViewModel]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManageScreen(
    cacheViewModel: CacheManageViewModel,
    storageViewModel: StorageManageViewModel,
    onBackClick: () -> Unit,
    onOpenPath: (String) -> Unit,
    onEvent: (CacheManageEvent) -> Unit
) {
    val state by cacheViewModel.uiState.collectAsStateWithLifecycle()
    val storageItems by storageViewModel.cacheItems.collectAsStateWithLifecycle()
    val storageTotalSize by storageViewModel.totalSize.collectAsStateWithLifecycle()
    val storageState by storageViewModel.uiState.collectAsStateWithLifecycle()
    val storageDialog by storageViewModel.dialog.collectAsStateWithLifecycle()

    var showStats by rememberSaveable { mutableStateOf(false) }
    val bookListState = rememberLazyListState()
    val statsListState = rememberLazyListState()
    val tabs = listOf(
        CacheManageMode.BOOK,
        CacheManageMode.AUDIO,
        CacheManageMode.VIDEO,
        CacheManageMode.MANGA,
        null
    )

    // 一次性事件（提示、"用缓存打开章节"）交给 Activity 执行平台操作
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, cacheViewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            cacheViewModel.events.collect(onEvent)
        }
    }

    val hasDialog = state.chapterDialog != null || state.confirm != null || storageDialog != null
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    DisposableEffect(hasDialog, backDispatcher) {
        val callback = object : OnBackPressedCallback(hasDialog) {
            override fun handleOnBackPressed() {
                when {
                    state.chapterDialog != null -> cacheViewModel.dismissChapterDialog()
                    state.confirm != null -> cacheViewModel.dismissConfirm()
                    else -> storageViewModel.dismissDialog()
                }
            }
        }
        backDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    LaunchedEffect(showStats) {
        if (showStats) storageViewModel.loadCacheInfo()
    }

    state.chapterDialog?.let { dialog ->
        CacheChapterDialog(
            state = dialog,
            onDismiss = cacheViewModel::dismissChapterDialog,
            onSearch = cacheViewModel::searchChapters,
            onFilterChange = cacheViewModel::switchChapterFilter,
            onChapterClick = { item ->
                if (dialog.selectionMode) {
                    cacheViewModel.toggleChapterSelection(item.chapter)
                } else {
                    cacheViewModel.openCachedChapter(dialog.book, item.chapter)
                }
            },
            onChapterLongClick = { item ->
                cacheViewModel.toggleChapterSelection(item.chapter)
            },
            onSelectAll = cacheViewModel::selectAllVisibleChapters,
            onClearSelection = cacheViewModel::clearChapterSelection,
            onCacheSelected = cacheViewModel::cacheSelectedChapters,
            onDeleteSelected = cacheViewModel::deleteSelectedChapters
        )
    }

    when (val confirm = state.confirm) {
        is CacheManageConfirm.DeleteChapters -> AppConfirmDialog(
            title = stringResource(R.string.delete),
            text = stringResource(
                R.string.cache_manage_delete_selected_confirm,
                confirm.chapters.size
            ),
            confirmText = stringResource(R.string.ok),
            destructive = true,
            onConfirm = cacheViewModel::confirmAction,
            onDismissRequest = cacheViewModel::dismissConfirm
        )
        is CacheManageConfirm.DeleteBook -> AppConfirmDialog(
            title = stringResource(R.string.delete),
            text = stringResource(
                R.string.cache_manage_delete_book_confirm,
                confirm.book.name
            ),
            confirmText = stringResource(R.string.ok),
            destructive = true,
            onConfirm = cacheViewModel::confirmAction,
            onDismissRequest = cacheViewModel::dismissConfirm
        )
        is CacheManageConfirm.DeleteAll -> AppConfirmDialog(
            title = stringResource(R.string.delete),
            text = stringResource(
                R.string.cache_manage_delete_all_confirm,
                confirm.books.size
            ),
            confirmText = stringResource(R.string.ok),
            destructive = true,
            onConfirm = cacheViewModel::confirmAction,
            onDismissRequest = cacheViewModel::dismissConfirm
        )
        null -> Unit
    }

    when (val dialog = storageDialog) {
        is StorageDialogState.ClearConfirm -> ClearConfirmDialog(
            targetName = dialog.detailId ?: storageViewModel.getCacheName(dialog.cacheType),
            onConfirm = {
                storageViewModel.clearCache(dialog.cacheType, dialog.detailId)
                storageViewModel.dismissDialog()
            },
            onDismiss = { storageViewModel.dismissDialog() }
        )
        is StorageDialogState.ClearAll -> ClearAllConfirmDialog(
            onConfirm = {
                storageViewModel.clearAllCache()
                storageViewModel.dismissDialog()
            },
            onDismiss = { storageViewModel.dismissDialog() }
        )
        null -> Unit
    }

    AppScaffold(
        topBar = {
            AppPageTopBar(
                title = stringResource(R.string.cache_manage_title),
                onBackClick = onBackClick
            ) {
                if (state.working) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = AppDimens.manageRowSpacing),
                        strokeWidth = AppDimens.dividerThickness
                    )
                }
                IconButton(
                    onClick = {
                        if (showStats) {
                            storageViewModel.loadCacheInfo()
                        } else {
                            cacheViewModel.load()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh)
                    )
                }
                if (!showStats) {
                    IconButton(onClick = cacheViewModel::requestDeleteAll) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.cache_manage_delete_all)
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!showStats) {
                CacheBatchBar(
                    enabled = state.items.any { it.cachedCount > 0 } && !state.working,
                    onUploadAll = cacheViewModel::uploadAllCaches,
                    onDeleteAll = cacheViewModel::requestDeleteAll
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SegmentedTabRow(
                tabs = tabs,
                progress = if (showStats) {
                    1f
                } else {
                    state.mode.ordinal.toFloat() / (tabs.size - 1)
                },
                onTabClick = { tab ->
                    if (tab == null) {
                        showStats = true
                    } else {
                        showStats = false
                        cacheViewModel.switchMode(tab)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppDimens.screenPadding,
                        top = AppDimens.manageTabTopPadding,
                        end = AppDimens.screenPadding
                    ),
                labelText = { tab ->
                    if (tab == null) {
                        stringResource(R.string.cache_manage_stats)
                    } else {
                        stringResource(tab.titleRes)
                    }
                }
            )
            if (showStats) {
                StorageStatsTab(
                    items = storageItems,
                    totalSize = storageTotalSize,
                    state = storageState,
                    listState = statsListState,
                    onExpand = { storageViewModel.toggleExpand(CacheType.valueOf(it.id)) },
                    onClear = { storageViewModel.requestClear(CacheType.valueOf(it.id)) },
                    onDetailClear = { id, detailId ->
                        storageViewModel.requestClear(CacheType.valueOf(id), detailId)
                    },
                    onOpenPath = onOpenPath,
                    onRetry = { storageViewModel.loadCacheInfo() },
                    onClearAll = { storageViewModel.requestClearAll() }
                )
            } else {
                CacheBookList(
                    state = state,
                    listState = bookListState,
                    onOpenChapters = cacheViewModel::openChapterDialog,
                    onUpload = cacheViewModel::uploadBookCache,
                    onUseCache = cacheViewModel::requestRestoreToBookshelf,
                    onDelete = cacheViewModel::requestDeleteBookCache,
                    onRetry = { cacheViewModel.load() }
                )
            }
        }
    }
}

@Composable
private fun CacheBookList(
    state: CacheManageUiState,
    listState: LazyListState,
    onOpenChapters: (CacheBookItem) -> Unit,
    onUpload: (CacheBookItem) -> Unit,
    onUseCache: (CacheBookItem) -> Unit,
    onDelete: (CacheBookItem) -> Unit,
    onRetry: () -> Unit
) {
    when {
        state.error != null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(AppDimens.cardSpacing))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(AppDimens.cardSpacing))
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.retry)
                    )
                }
            }
        }
        state.loading && state.items.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        state.items.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(
                    R.string.cache_manage_empty,
                    stringResource(state.mode.titleRes)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = AppDimens.cardSpacing,
                    bottom = AppDimens.screenPadding,
                    start = AppDimens.screenPadding,
                    end = AppDimens.screenPadding
                ),
                verticalArrangement = Arrangement.spacedBy(AppDimens.cardSpacing)
            ) {
                items(state.items, key = { it.book.bookUrl }) { item ->
                    CacheManageBookCard(
                        item = item,
                        onOpenChapters = { onOpenChapters(item) },
                        onUpload = { onUpload(item) },
                        onUseCache = { onUseCache(item) },
                        onDelete = { onDelete(item) }
                    )
                }
            }
            VerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * 统计页：沿用原有按缓存类型清点的实现，含明细与"打开路径"
 */
@Composable
private fun StorageStatsTab(
    items: List<CacheItem>,
    totalSize: Long,
    state: StorageUiState,
    listState: LazyListState,
    onExpand: (CacheItem) -> Unit,
    onClear: (CacheItem) -> Unit,
    onDetailClear: (String, String) -> Unit,
    onOpenPath: (String) -> Unit,
    onRetry: () -> Unit,
    onClearAll: () -> Unit
) {
    when (state) {
        is StorageUiState.Clearing -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(AppDimens.cardSpacing))
                Text(
                    text = stringResource(R.string.storage_clearing, state.target),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is StorageUiState.Error -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(AppDimens.cardSpacing))
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.retry)
                    )
                }
            }
        }
        else -> Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = AppDimens.cardSpacing,
                    bottom = AppDimens.screenPadding,
                    start = AppDimens.screenPadding,
                    end = AppDimens.screenPadding
                ),
                verticalArrangement = Arrangement.spacedBy(AppDimens.cardSpacing)
            ) {
                item {
                    CacheSummaryCard(
                        totalSize = totalSize,
                        itemCount = items.size
                    )
                }
                items(items, key = { it.id }) { item ->
                    CacheItemCard(
                        item = item,
                        onExpandClick = { onExpand(item) },
                        onClearClick = { onClear(item) },
                        onDetailClearClick = { detailId -> onDetailClear(item.id, detailId) },
                        onOpenPathClick = onOpenPath
                    )
                }
                item {
                    BatchTextButton(
                        text = stringResource(R.string.storage_clear_all),
                        onClick = onClearAll
                    )
                }
            }
            VerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun CacheBatchBar(
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
        BatchTextButton(
            text = stringResource(R.string.cache_manage_upload_all),
            onClick = onUploadAll,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(AppDimens.manageActionSpacing))
        BatchTextButton(
            text = stringResource(R.string.cache_manage_delete_all),
            onClick = onDeleteAll,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BatchTextButton(
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
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                },
                maxLines = 1
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.45f
