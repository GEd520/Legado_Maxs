package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.book.BookTagManagement
import io.legado.app.help.book.SmartTag
import io.legado.app.help.book.SmartTagConfig
import io.legado.app.help.book.toSmartTagSnapshot
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架标签管理 ViewModel。
 *
 * 使用裸 [viewModelScope.launch] 而非 [io.legado.app.help.coroutine.Coroutine]，
 * 因为本 ViewModel 不继承 [io.legado.app.base.BaseViewModel]，且变更操作需同步更新 UiState。
 */
class BookshelfTagManageViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private var focusGroupId: Long = BookGroup.IdAll

    private val _uiState = MutableStateFlow(BookshelfTagManageUiState())
    val uiState: StateFlow<BookshelfTagManageUiState> = _uiState.asStateFlow()

    fun setFocusGroupId(groupId: Long) {
        focusGroupId = groupId
        loadTags()
    }

    /**
     * 加载所有分组及其标签数据。
     */
    fun loadTags() {
        _uiState.value = _uiState.value.copy(loading = true)
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val books = appDb.bookDao.allTagInfos
                val groups = appDb.bookGroupDao.all
                    .filter { it.groupId != BookGroup.IdRoot }
                    .sortedBy { it.order }
                val userGroupMask = groups.asSequence()
                    .filter { it.groupId > 0 }
                    .fold(0L) { acc, group -> acc or group.groupId }
                // 分组删除后可能在配置里留下孤儿标签项：这里统一丢弃并写回，
                // 否则它们只在书籍详情页的可选标签里出现，管理页却无法删除
                val validGroupIds = groups.mapTo(HashSet()) { it.groupId }
                val storedTagMap = AppConfig.bookshelfGroupTags
                val configuredMap =
                    BookTagManagement.pruneUnknownGroups(storedTagMap, validGroupIds).toMutableMap()
                var configuredChanged = configuredMap.size != storedTagMap.size
                val storedHiddenMap = AppConfig.bookshelfHiddenTags
                val hiddenMap = BookTagManagement.pruneUnknownGroups(storedHiddenMap, validGroupIds)
                var hiddenChanged = hiddenMap.size != storedHiddenMap.size
                val result = groups.mapNotNull { group ->
                    val groupBooks = booksInGroup(group, books, userGroupMask)
                    val existingTags = groupBooks
                        .flatMap { BookTagHelper.parse(it.customTag) }
                    val configuredTags = configuredMap[group.groupId].orEmpty()
                    val tags = BookTagManagement.mergeTags(configuredTags, existingTags)
                    if (configuredTags != tags) {
                        configuredMap[group.groupId] = tags
                        configuredChanged = true
                    }
                    val hiddenTags = hiddenMap[group.groupId].orEmpty()
                    BookshelfTagGroupUi(
                        groupId = group.groupId,
                        groupName = group.groupName,
                        books = groupBooks,
                        tags = tags.map { tag ->
                            BookshelfTagItemUi(
                                name = tag,
                                assignedCount = groupBooks.count {
                                    BookTagHelper.has(it.customTag, tag)
                                },
                                visible = hiddenTags.none {
                                    it.equals(tag, ignoreCase = true)
                                },
                            )
                        },
                    )
                }
                if (configuredChanged) {
                    AppConfig.bookshelfGroupTags = configuredMap
                }
                if (hiddenChanged) {
                    AppConfig.bookshelfHiddenTags = hiddenMap
                }
                // 智能标签：规则名/说明取决于语言，规则开关取自偏好；命中数量按全库书籍统计
                val context = getApplication<Application>()
                val resolvedRules = SmartTag.resolve(context)
                val snapshots = books.map { it.toSmartTagSnapshot() }
                val disabledRules = SmartTagConfig.disabledRuleIds(context)
                val smartItems = resolvedRules.map { rule ->
                    SmartTagItemUi(
                        id = rule.id,
                        name = rule.name,
                        description = rule.description,
                        assignedCount = snapshots.count { rule.match(it) },
                        enabled = rule.id !in disabledRules,
                    )
                }
                Triple(result, smartItems, SmartTagConfig.isEnabled(context))
            }
            _uiState.value = _uiState.value.copy(
                groups = data.first,
                smartTags = data.second,
                smartTagsEnabled = data.third,
                focusGroupId = focusGroupId,
                loading = false,
            )
        }
    }

    /**
     * 打开智能标签管理对话框。
     */
    fun showSmartTagDialog() {
        _uiState.value = _uiState.value.copy(dialog = BookshelfTagDialogState.SmartTags)
    }

    /**
     * 开关智能标签总开关。
     */
    fun setSmartTagsEnabled(enabled: Boolean) {
        SmartTagConfig.setEnabled(getApplication<Application>(), enabled)
        _uiState.value = _uiState.value.copy(smartTagsEnabled = enabled)
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    /**
     * 开关某个智能标签（子标签）。
     */
    fun setSmartTagEnabled(ruleId: String, enabled: Boolean) {
        SmartTagConfig.setRuleEnabled(getApplication<Application>(), ruleId, enabled)
        _uiState.value = _uiState.value.copy(
            smartTags = _uiState.value.smartTags.map {
                if (it.id == ruleId) it.copy(enabled = enabled) else it
            },
        )
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    /**
     * 打开添加标签对话框，让用户输入或选择标签。
     */
    fun showAddTagDialog(groupId: Long, groupName: String) {
        _uiState.value = _uiState.value.copy(
            dialog = BookshelfTagDialogState.AddTags(groupId, groupName),
        )
    }

    fun addTags(groupId: Long, tags: List<String>) {
        val newTags = BookTagManagement.mergeTags(emptyList(), tags)
        if (newTags.isEmpty()) return
        val map = AppConfig.bookshelfGroupTags.toMutableMap()
        map[groupId] = BookTagManagement.mergeTags(map[groupId].orEmpty(), newTags)
        AppConfig.bookshelfGroupTags = map
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
        loadTags()
    }

    fun setTagVisible(groupId: Long, tag: String, visible: Boolean) {
        val map = AppConfig.bookshelfHiddenTags.toMutableMap()
        val tags = map[groupId].orEmpty().toMutableSet()
        tags.removeAll { it.equals(tag, ignoreCase = true) }
        if (!visible) tags.add(tag)
        if (tags.isEmpty()) map.remove(groupId) else map[groupId] = tags
        AppConfig.bookshelfHiddenTags = map
        val groups = _uiState.value.groups.map { group ->
            if (group.groupId != groupId) {
                group
            } else {
                group.copy(
                    tags = group.tags.map { item ->
                        if (item.name.equals(tag, ignoreCase = true)) item.copy(visible = visible) else item
                    },
                )
            }
        }
        _uiState.value = _uiState.value.copy(groups = groups)
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    fun startManageBooks(group: BookshelfTagGroupUi, tag: String) {
        _uiState.value = _uiState.value.copy(
            dialog = BookshelfTagDialogState.ManageBooks(
                BookTagAssignmentUi(
                    groupId = group.groupId,
                    groupName = group.groupName,
                    tag = tag,
                    books = group.books,
                    initiallySelectedUrls = group.books.asSequence()
                        .filter { BookTagHelper.has(it.customTag, tag) }
                        .mapTo(linkedSetOf()) { it.bookUrl },
                ),
            ),
        )
    }

    fun confirmDeleteTag(group: BookshelfTagGroupUi, tag: String) {
        _uiState.value = _uiState.value.copy(
            dialog = BookshelfTagDialogState.DeleteConfirm(
                groupId = group.groupId,
                groupName = group.groupName,
                tag = tag,
                books = group.books,
            ),
        )
    }

    fun confirmRenameTag(tag: String) {
        _uiState.value = _uiState.value.copy(
            dialog = BookshelfTagDialogState.RenameTag(oldTag = tag),
        )
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(dialog = null)
    }

    fun saveAssignment(assignment: BookTagAssignmentUi, selectedUrls: Set<String>) {
        dismissDialog()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                appDb.withTransaction {
                    assignment.books.forEach { book ->
                        val shouldHaveTag = book.bookUrl in selectedUrls
                        val write = BookTagManagement.updateTag(
                            customTag = book.customTag,
                            tag = assignment.tag,
                            selected = shouldHaveTag,
                        ) ?: return@forEach
                        appDb.bookDao.updateCustomTag(book.bookUrl, write.customTag)
                    }
                }
            }
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            loadTags()
        }
    }

    fun executeDeleteTag(groupId: Long, groupName: String, tag: String, books: List<BookTagInfo>) {
        dismissDialog()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                appDb.withTransaction {
                    books.forEach { book ->
                        val write = BookTagManagement.updateTag(
                            customTag = book.customTag,
                            tag = tag,
                            selected = false,
                        ) ?: return@forEach
                        appDb.bookDao.updateCustomTag(book.bookUrl, write.customTag)
                    }
                }
                val hiddenMap = AppConfig.bookshelfHiddenTags.toMutableMap()
                hiddenMap[groupId] = hiddenMap[groupId].orEmpty()
                    .filterNot { it.equals(tag, ignoreCase = true) }
                    .toSet()
                if (hiddenMap[groupId].isNullOrEmpty()) hiddenMap.remove(groupId)
                AppConfig.bookshelfHiddenTags = hiddenMap
                val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
                tagMap[groupId] = tagMap[groupId].orEmpty()
                    .filterNot { it.equals(tag, ignoreCase = true) }
                AppConfig.bookshelfGroupTags = tagMap
            }
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            loadTags()
        }
    }

    /**
     * 执行标签重命名。
     *
     * 标签以文本形式存在每本书的 customTag 中，所以改名必须**全局**生效：覆盖所有带旧标签的
     * 书籍（不限当前分组）以及所有分组的标签配置（含隐藏列表）。只改当前分组会让其他分组
     * 留下同名的空标签，观感就像"改名时新建了一个标签、旧标签没删掉"。
     */
    fun executeRenameTag(oldTag: String, newTag: String) {
        dismissDialog()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                appDb.withTransaction {
                    appDb.bookDao.allTagInfos.forEach { book ->
                        if (!BookTagHelper.has(book.customTag, oldTag)) return@forEach
                        val tags = BookTagHelper.parse(book.customTag).toMutableList()
                        val index = tags.indexOfFirst { it.equals(oldTag, ignoreCase = true) }
                        if (index < 0) return@forEach
                        tags[index] = newTag
                        // 新名与已有标签冲突时去重，保证一本书里只留一个
                        val deduped = tags.distinctBy { it.lowercase(java.util.Locale.ROOT) }
                        appDb.bookDao.updateCustomTag(book.bookUrl, BookTagHelper.join(deduped))
                    }
                }
                // 所有分组的标签配置与隐藏标签同样改名，避免别处残留同名旧标签
                AppConfig.bookshelfGroupTags = BookTagManagement.renameInGroups(
                    AppConfig.bookshelfGroupTags,
                    oldTag,
                    newTag,
                )
                AppConfig.bookshelfHiddenTags = BookTagManagement.renameInHiddenGroups(
                    AppConfig.bookshelfHiddenTags,
                    oldTag,
                    newTag,
                )
            }
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            loadTags()
        }
    }

    /**
     * 保存标签排序结果到配置。
     */
    fun reorderTags(groupId: Long, newOrder: List<String>) {
        val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
        val currentTags = tagMap[groupId].orEmpty()
        // 保留 mergeTags 时从书籍中自动发现的、不在 newOrder 中的标签（追加到末尾）
        val remaining = currentTags.filterNot { tag ->
            newOrder.any { it.equals(tag, ignoreCase = true) }
        }
        tagMap[groupId] = newOrder + remaining
        AppConfig.bookshelfGroupTags = tagMap
        // 乐观更新 UI 状态
        val groups = _uiState.value.groups.map { group ->
            if (group.groupId != groupId) {
                group
            } else {
                group.copy(
                    tags = newOrder.mapNotNull { tag ->
                        group.tags.firstOrNull { it.name.equals(tag, ignoreCase = true) }
                    } + group.tags.filterNot { item ->
                        newOrder.any { it.equals(item.name, ignoreCase = true) }
                    },
                )
            }
        }
        _uiState.value = _uiState.value.copy(groups = groups)
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    @Suppress("FunctionName")
    private fun booksInGroup(
        group: BookGroup,
        books: List<BookTagInfo>,
        userGroupMask: Long,
    ): List<BookTagInfo> = when (group.groupId) {
        BookGroup.IdAll -> books
        BookGroup.IdLocal -> books.filter { it.type and BookType.local > 0 }
        BookGroup.IdAudio -> books.filter { it.type and BookType.audio > 0 }
        BookGroup.IdVideo -> books.filter { it.type and BookType.video > 0 }
        BookGroup.IdError -> books.filter { it.type and BookType.updateError > 0 }
        BookGroup.IdNetNone -> books.filter {
            it.type and BookType.audio == 0 &&
                it.type and BookType.video == 0 &&
                it.type and BookType.local == 0 &&
                (it.group and userGroupMask) == 0L
        }
        BookGroup.IdLocalNone -> books.filter {
            it.type and BookType.audio == 0 &&
                it.type and BookType.video == 0 &&
                it.type and BookType.local > 0 &&
                (it.group and userGroupMask) == 0L
        }
        else -> if (group.groupId > 0) {
            books.filter { it.group and group.groupId > 0 }
        } else {
            emptyList()
        }
    }
}
