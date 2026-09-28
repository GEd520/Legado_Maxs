package io.legado.app.ui.main.bookshelf

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.book.BookTagManagement
import io.legado.app.help.book.BookTagMatcher
import io.legado.app.help.book.toSmartTagSnapshot
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.flowWithLifecycleFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架二级标签栏的数据计算（style1 与 style2 共用同一份实现）。
 *
 * 标签来源为「当前分组内实际使用/命中的标签」与「用户手动配置的标签」的并集，
 * 减去被隐藏的标签；智能标签只保留本分组内有书籍命中的规则。
 *
 * @return 标签名列表，以及每个标签的命中数量；空字符串 key 代表"全部"标签，
 * 其数量即分组内书籍总数，供 "标签名·数量" 展示
 */
internal suspend fun loadBookshelfTagBarData(
    context: Context,
    groupId: Long,
): Pair<List<String>, Map<String, Int>> = withContext(Dispatchers.IO) {
    val configured = AppConfig.bookshelfGroupTags[groupId].orEmpty()
    val hidden = AppConfig.bookshelfHiddenTags[groupId].orEmpty()
    val groupBooks = filterBookshelfTagInfosByGroup(appDb.bookDao.allTagInfos, groupId)
    // 每本书的标签只解析一次，后续合并标签与统计数量复用
    val parsedTags = groupBooks.map { BookTagHelper.parseSet(it.customTag) }
    val existing = parsedTags.flatten()
    val merged = BookTagManagement.mergeTags(configured, existing)
        .filter { tag -> hidden.none { it.equals(tag, ignoreCase = true) } }
    val smartRules = BookTagMatcher.enabledRules(context)
    val snapshots = groupBooks.map { it.toSmartTagSnapshot() }
    val smartNames = BookTagMatcher.matchingNames(snapshots, smartRules)
    val mergedTags = BookTagManagement.mergeTags(merged, smartNames)
    val counts = BookTagMatcher.countMatches(
        mergedTags,
        parsedTags,
        snapshots,
        smartRules,
    ) + ("" to groupBooks.size)
    mergedTags to counts
}

/**
 * 让二级标签栏跟随 `books` 表变化重算。
 *
 * 智能标签（未读/在读/读完/有更新…）的数量由书籍字段决定，这些字段的写入散落在
 * 阅读进度保存（[io.legado.app.model.ReadBook.saveRead]）、目录更新、换源、导入等许多地方，
 * 靠写入方逐个补发 `EventBus.BOOKSHELF_REFRESH` 必然漏发——表现就是"书读完回到书架，
 * 未读标签的数量还是旧的，切一次分组才更新"（切分组会重新走 [loadBookshelfTagBarData]）。
 *
 * 因此这里不看事件，直接盯住 [appDb.bookDao.flowAllTagInfos]：标签判定相关的列变了就重算。
 * 投影列与 [BookTagInfo] 一致，`distinctUntilChanged` 会把与标签无关的写入
 * （封面、简介、阅读时间等）滤掉，不会让封面缓存之类的心跳触发无谓重算。
 *
 * 必须在视图已创建后调用（用到 [Fragment.viewLifecycleOwner] 的生命周期与协程作用域）。
 */
internal fun Fragment.observeBookshelfTagSource(onTagSourceChanged: () -> Unit) {
    val owner = viewLifecycleOwner
    owner.lifecycleScope.launch {
        appDb.bookDao.flowAllTagInfos
            .flowWithLifecycleFirst(owner.lifecycle)
            .conflate()
            .distinctUntilChanged()
            .collect { onTagSourceChanged() }
    }
}

/**
 * 主界面重建后要恢复的二级标签选中态（进程级暂存）。
 *
 * 切日夜主题时 `MainActivity` 走「清任务 + 全新启动」重建（见 `MainActivity.recreate`），
 * 书架 Fragment 会被重建，而 CLEAR_TASK 启动拿不到 savedInstanceState——与
 * `MainActivity.lastTabFragmentId` 同理，这类「重建后要保持的界面状态」只能放进程级存储。
 *
 * 一次性语义：只在重建后**第一次**加载那个分组的标签栏时消费，消费后即清空，
 * 这样用户手动切分组仍会回到「全部」（沿用别的分组的筛选没有意义）。
 * 进程被杀后自然清空，重进 App 回到「全部」是符合预期的。
 */
internal object BookshelfTagSelection {

    private var pendingGroupId: Long? = null
    private var pendingTag: String? = null

    /** 记下某分组当前选中的标签（空串表示「全部」），供下次重建恢复 */
    fun remember(groupId: Long, tag: String?) {
        pendingGroupId = groupId
        pendingTag = tag
    }

    /** 取出该分组待恢复的选中标签；分组不匹配或没有记录时返回 null（调用方按「全部」处理） */
    fun consume(groupId: Long): String? {
        if (pendingGroupId != groupId) return null
        val tag = pendingTag
        pendingGroupId = null
        pendingTag = null
        return tag
    }

    /** 只看不取：给孩子页当初始筛选用，不能影响 [consume] 的一次性语义 */
    fun peek(groupId: Long): String? =
        if (pendingGroupId == groupId) pendingTag?.takeIf { it.isNotEmpty() } else null
}

/**
 * 在重算后的标签列表里找回上次选中的标签，找不到时回落到「全部」（索引 0）。
 *
 * 标签栏会随书籍标签变更、主题切换等原因整份重算，重算时不能用固定索引（下标会错位），
 * 也不能无条件回到「全部」——那会把用户的筛选状态冲掉。只有**同一分组内**重载才该调用，
 * 不同分组各有各的标签列表，跨分组沿用没有意义。
 *
 * @param tag 上次选中的标签名，空串/null 代表「全部」
 */
internal fun List<String>.restoreTagSelection(tag: String?): Int {
    if (tag.isNullOrEmpty()) return 0
    val exact = indexOf(tag)
    if (exact >= 0) return exact
    val ignoreCase = indexOfFirst { it.equals(tag, ignoreCase = true) }
    return if (ignoreCase >= 0) ignoreCase else 0
}

/**
 * 按分组筛选书籍标签信息，逻辑与 [BookshelfTagManageViewModel.booksInGroup] 一致。
 * 默认分组（负数 ID）基于 [BookType] 筛选，用户分组（正数 ID）基于 group 位掩码筛选。
 */
internal fun filterBookshelfTagInfosByGroup(
    books: List<BookTagInfo>,
    groupId: Long,
): List<BookTagInfo> = when (groupId) {
    BookGroup.IdAll -> books
    BookGroup.IdLocal -> books.filter { it.type and BookType.local > 0 }
    BookGroup.IdAudio -> books.filter { it.type and BookType.audio > 0 }
    BookGroup.IdVideo -> books.filter { it.type and BookType.video > 0 }
    BookGroup.IdError -> books.filter { it.type and BookType.updateError > 0 }
    else -> {
        val userGroupMask = appDb.bookGroupDao.all
            .filter { it.groupId > 0 }
            .fold(0L) { acc, group -> acc or group.groupId }
        when (groupId) {
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

            else -> if (groupId > 0) {
                books.filter { it.group and groupId > 0 }
            } else {
                emptyList()
            }
        }
    }
}
