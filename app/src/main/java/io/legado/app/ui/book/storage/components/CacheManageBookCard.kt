package io.legado.app.ui.book.storage.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.R
import io.legado.app.ui.book.storage.CacheBookItem
import io.legado.app.ui.book.storage.toCacheSizeText
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composeActionShape
import io.legado.app.ui.theme.composePanelShape
import io.legado.app.ui.widget.components.AppBookCover

/**
 * 缓存管理列表里的一本书
 *
 * 展示"这本书在这类缓存下有多少章已缓存、占多大"，操作按钮与参考分支一致：
 * 章节列表 / 上传 / 使用缓存（或加入书架）/ 删除
 */
@Composable
fun CacheManageBookCard(
    item: CacheBookItem,
    onOpenChapters: () -> Unit,
    onUpload: () -> Unit,
    onUseCache: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val book = item.book
    val hasCache = item.cachedCount > 0
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = composePanelShape(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppDimens.manageCardPadding),
            verticalAlignment = Alignment.Top
        ) {
            AppBookCover(
                modifier = Modifier.size(AppDimens.manageCoverWidth, AppDimens.manageCoverHeight),
                name = book.name,
                author = book.author,
                coverPath = book.coverUrl,
                galleryIdentity = book.bookUrl,
                contentDescription = book.name,
                sourceOrigin = book.origin
            )
            Spacer(Modifier.width(AppDimens.shelfCoverNameSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (book.originName.isNotBlank()) {
                        Spacer(Modifier.width(AppDimens.manageRowSpacing))
                        Text(
                            text = book.originName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(AppDimens.manageRowSpacing))
                Text(
                    text = if (item.storageCalculated) {
                        stringResource(
                            R.string.cache_manage_cached_count_with_size,
                            item.cachedCount,
                            item.totalChapterCount,
                            item.storageSizeBytes.toCacheSizeText()
                        )
                    } else {
                        //缓存明细还在后台逐本算：先只显示书名与"计算中"，不显示会被误读的 0
                        stringResource(R.string.cache_manage_size_calculating)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AppDimens.manageRowSpacing))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    ManageActionButton(
                        text = stringResource(R.string.chapter_list),
                        onClick = onOpenChapters
                    )
                    Spacer(Modifier.width(AppDimens.manageActionSpacing))
                    ManageActionButton(
                        text = stringResource(R.string.cache_manage_upload),
                        onClick = onUpload,
                        enabled = hasCache
                    )
                    Spacer(Modifier.width(AppDimens.manageActionSpacing))
                    ManageActionButton(
                        text = stringResource(
                            if (item.inBookshelf) {
                                R.string.cache_manage_use_cache
                            } else {
                                R.string.cache_manage_add_bookshelf
                            }
                        ),
                        onClick = onUseCache
                    )
                    Spacer(Modifier.width(AppDimens.manageActionSpacing))
                    ManageActionButton(
                        text = stringResource(R.string.delete),
                        onClick = onDelete,
                        enabled = hasCache
                    )
                }
            }
        }
    }
}

/** 卡片与弹窗共用的操作按钮（胶囊按钮，圆角与 XML 侧同源） */
@Composable
fun ManageActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = composeActionShape(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(
            modifier = Modifier
                .height(AppDimens.manageActionHeight)
                .defaultMinSize(minWidth = AppDimens.manageActionMinWidth)
                .padding(horizontal = AppDimens.manageActionPaddingHorizontal),
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
