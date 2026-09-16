package io.legado.app.ui.book.group

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.postEvent

class GroupViewModel(application: Application) : BaseViewModel(application) {

    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
        execute {
            appDb.bookGroupDao.update(*bookGroup)
        }.onFinally {
            finally?.invoke()
        }
    }

    fun addGroup(
        groupName: String,
        bookSort: Int,
        enableRefresh: Boolean,
        onlyUpdateRead: Boolean,
        cover: String?,
        finally: () -> Unit,
    ) {
        execute {
            val groupId = appDb.bookGroupDao.getUnusedId()
            val bookGroup = BookGroup(
                groupId = groupId,
                groupName = groupName,
                cover = cover,
                bookSort = bookSort,
                enableRefresh = enableRefresh,
                onlyUpdateRead = onlyUpdateRead,
                order = appDb.bookGroupDao.maxOrder.plus(1),
            )
            appDb.bookGroupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
            appDb.bookGroupDao.insert(bookGroup)
        }.onFinally {
            finally()
        }
    }

    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) {
        execute {
            appDb.bookGroupDao.delete(bookGroup)
            appDb.bookDao.removeGroup(bookGroup.groupId)
            // 一并清理该分组的标签配置：否则会留下"管理标签页删不掉、却仍在书籍详情页可选标签里
            // 出现"的孤儿标签（分组已不存在，管理页不会再列出它）
            val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
            if (tagMap.remove(bookGroup.groupId) != null) {
                AppConfig.bookshelfGroupTags = tagMap
            }
            val hiddenMap = AppConfig.bookshelfHiddenTags.toMutableMap()
            if (hiddenMap.remove(bookGroup.groupId) != null) {
                AppConfig.bookshelfHiddenTags = hiddenMap
            }
        }.onFinally {
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            finally()
        }
    }
}
