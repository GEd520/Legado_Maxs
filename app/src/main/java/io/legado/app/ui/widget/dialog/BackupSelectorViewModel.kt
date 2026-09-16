package io.legado.app.ui.widget.dialog

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.storage.BackupInfoHelper
import io.legado.app.help.storage.BackupSelectorConfig
import io.legado.app.help.storage.BackupSelectorConfig.Scope
import io.legado.app.ui.widget.components.dialog.MultiSelectGroup
import io.legado.app.ui.widget.components.dialog.MultiSelectItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 备份选择器界面状态。
 *
 * 这个状态只服务于弹窗展示：加载中显示进度，加载完成后提供分组数据、当前备份目标
 * 以及每个备份目标各自的选择结果。
 */
sealed class BackupSelectorUiState {
    object Loading : BackupSelectorUiState()

    data class Content(
        val groups: List<MultiSelectGroup>,
        /** 当前展示的备份目标选项卡 */
        val scope: Scope,
        /** 各备份目标独立的勾选结果 */
        val selections: Map<Scope, Set<String>>
    ) : BackupSelectorUiState() {

        /** 当前选项卡的勾选结果 */
        val selectedKeys: Set<String>
            get() = selections[scope].orEmpty()
    }
}

/**
 * 备份选择器的 ViewModel。
 *
 * 负责加载备份项详情、维护弹窗内两个备份目标各自的勾选状态，并在用户确认时统一写回配置。
 */
class BackupSelectorViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow<BackupSelectorUiState>(BackupSelectorUiState.Loading)
    val uiState: StateFlow<BackupSelectorUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        // 备份概览会读取数据库数量和文件大小，必须放到 IO 线程，避免阻塞主线程。
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = buildUiState()
        }
    }

    /**
     * 切换备份目标选项卡。
     *
     * 两个选项卡各自维护独立的勾选结果，切换只改变当前展示的目标，不会丢失已改动。
     */
    fun onScopeChange(scope: Scope) {
        updateContent { it.copy(scope = scope) }
    }

    /**
     * 处理用户选择项变化事件。
     *
     * 只影响当前选项卡所属的备份目标。
     *
     * @param key 被选择项的键值。
     * @param isSelected 如果为 true，则添加到选中项集合；否则从集合中移除。
     */

    fun onSelectionChange(key: String, isSelected: Boolean) {
        updateContent { content ->
            val keys = content.selectedKeys
            val newKeys = if (isSelected) keys + key else keys - key
            content.copy(selections = content.selections + (content.scope to newKeys))
        }
    }

    /**
     * 全选当前选项卡的所有备份项。
     */
    fun selectAll() {
        updateContent { content ->
            content.copy(selections = content.selections + (content.scope to allKeys()))
        }
    }

    /**
     * 取消全选当前选项卡的所有备份项。
     */
    fun deselectAll() {
        updateContent { content ->
            content.copy(selections = content.selections + (content.scope to emptySet()))
        }
    }

    fun saveSelection() {
        val content = _uiState.value as? BackupSelectorUiState.Content ?: return
        // 弹窗内的勾选变化先保存在内存中，只有用户确认关闭时才写回配置文件；
        // 本地与云端的勾选结果分别写回，互不影响。
        content.selections.forEach { (scope, keys) ->
            BackupSelectorConfig.setSelectedKeys(keys, scope)
        }
        BackupSelectorConfig.save()
    }

    /**
     * 格式化选中项的总大小。
     *
     * @param selectedItems 选中的备份项列表。
     * @return 格式化后的总大小字符串。
     */
    fun formatTotalSize(selectedItems: List<MultiSelectItem>): String {
        return BackupInfoHelper.formatSize(selectedItems.sumOf { it.rawSize ?: 0L })
    }

    private fun updateContent(
        block: (BackupSelectorUiState.Content) -> BackupSelectorUiState.Content
    ) {
        val content = _uiState.value as? BackupSelectorUiState.Content ?: return
        _uiState.value = block(content)
    }

    private fun allKeys(): Set<String> = BackupSelectorConfig.allItems.map { it.key }.toSet()

    private fun buildUiState(): BackupSelectorUiState.Content {
        // 在 ViewModel 中把存储层的备份定义转换成通用多选弹窗模型，
        // 避免 BackupSelectorConfig 反向依赖 UI 组件类。
        val overview = BackupInfoHelper.getBackupOverview()
        val fileInfoByName = overview.items.associateBy { it.fileName }
        // 各备份目标加载自己独立的勾选结果
        val selections = Scope.values()
            .associateWith { BackupSelectorConfig.getSelectedKeys(it) }
        val localKeys = selections[Scope.Local].orEmpty()
        val groups = BackupSelectorConfig.groupItems.map { (groupName, items) ->
            MultiSelectGroup(
                name = groupName,
                iconEmoji = BackupSelectorConfig.getGroupIcon(groupName),
                items = items.map { item ->
                    val fileInfo = fileInfoByName[item.overviewFileName()]
                    val countInfo = BackupInfoHelper.getItemCount(item.key)
                        .takeIf { it > 0 }
                        ?.let { "$it 个" }

                    MultiSelectItem(
                        key = item.key,
                        title = item.title,
                        subtitle = item.fileName,
                        size = fileInfo?.let { BackupInfoHelper.formatSize(it.size) },
                        rawSize = fileInfo?.size,
                        count = countInfo,
                        group = item.group,
                        iconEmoji = item.iconEmoji,
                        selected = item.key in localKeys
                    )
                }
            )
        }
        return BackupSelectorUiState.Content(
            groups = groups,
            scope = Scope.Local,
            selections = selections
        )
    }

    private fun BackupSelectorConfig.BackupItem.overviewFileName(): String {
        return when (key) {
            // 选择器持久化的是 "bg"，但 BackupInfoHelper 统计时使用的是展示分组 key。
            "backgroundImages" -> "backgroundImages"
            // BackupSelectorConfig 中 fileName 与 BackupInfoHelper 使用的常量不一致的条目。
            "readShareConfig" -> ReadBookConfig.shareConfigFileName
            "directLinkRule" -> DirectLinkUpload.ruleFileName
            "bookChapter" -> "bookChapterCache.json"
            else -> fileName
        }
    }
}
