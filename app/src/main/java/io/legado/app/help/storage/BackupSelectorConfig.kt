package io.legado.app.help.storage

import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

/**
 * 备份选择器配置。
 *
 * 这个文件只维护可备份项定义和用户选择结果的读写，不依赖具体 UI 组件。
 *
 * 本地备份与 WebDAV 云备份各自维护一份独立的勾选结果（见 [Scope]），
 * 两条备份链路互不干扰：本地备份按 [Scope.Local] 打包，
 * 上传到 WebDAV 的备份包按 [Scope.WebDav] 打包。
 * 旧版本只有一份勾选结果，升级时本地与云端都沿用该结果，保证升级后行为不变。
 */
@Suppress("ConstPropertyName")
object BackupSelectorConfig {

    /**
     * 备份目标，用于区分本地备份与 WebDAV 云备份各自的选择结果。
     */
    enum class Scope(val key: String) {
        Local("local"),
        WebDav("webDav");

        companion object {
            fun fromKey(key: String?): Scope? = values().firstOrNull { it.key == key }
        }
    }

    private val configPath = FileUtils.getPath(appCtx.filesDir, "backupSelector.json")

    data class BackupItem(
        val key: String,
        val fileName: String,
        val title: String,
        val group: String,
        val iconEmoji: String? = null, // Emoji图标
    )

    val allItems = listOf(
        BackupItem("coverGallery", CoverGalleryRepository.backupDirName, "封面图集", "配置", "🖼️"),
        BackupItem("bookshelf", "bookshelf.json", "书架", "数据库", "📚"),
        BackupItem("bookChapter", "bookChapter.json", "章节目录", "数据库", "📖"),
        BackupItem("bookmark", "bookmark.json", "书签", "数据库", "🔖"),
        BackupItem("bookGroup", "bookGroup.json", "书籍分组", "数据库", "📁"),
        BackupItem("bookSource", "bookSource.json", "书源", "数据库", "📗"),
        BackupItem("rssSources", "rssSources.json", "订阅源", "数据库", "📰"),
        BackupItem("rssStar", "rssStar.json", "订阅收藏", "数据库", "⭐"),
        BackupItem("sourceSub", "sourceSub.json", "源订阅链接", "数据库", "🔗"),
        BackupItem("webSearchEngines", "webSearchEngines.json", "搜索引擎规则", "配置", "🔍"),
        BackupItem("replaceRule", "replaceRule.json", "替换规则", "数据库", "🔧"),
        BackupItem("highlightRule", "highlightRule.json", "高亮规则", "配置", "✨"),
        BackupItem("readRecord", "readRecord.json", "阅读记录", "数据库", "📊"),
        BackupItem("readRecordDetail", "readRecordDetail.json", "阅读记录详情", "数据库", "📝"),
        BackupItem("readRecordSession", "readRecordSession.json", "阅读时段", "数据库", "⏱️"),
        BackupItem("searchHistory", "searchHistory.json", "搜索历史", "数据库", "🔍"),
        BackupItem("txtTocRule", "txtTocRule.json", "TXT目录规则", "数据库", "📋"),
        BackupItem("httpTTS", "httpTTS.json", "TTS配置", "数据库", "🔊"),
        BackupItem("keyboardAssists", "keyboardAssists.json", "键盘辅助", "数据库", "⌨️"),
        BackupItem("dictRule", "dictRule.json", "词典规则", "数据库", "📖"),
        BackupItem("servers", "servers.json", "服务器配置", "数据库", "🖥️"),
        BackupItem("runtimeSourceCache", "runtimeSourceCache.json", "书源运行数据", "数据库", "⚡"),
        BackupItem("readConfig", "readConfig.json", "阅读样式配置", "配置", "🎨"),
        BackupItem("readShareConfig", "readShareConfig.json", "阅读分享配置", "配置", "📤"),
        BackupItem("themeConfig", "themeConfig.json", "主题配置", "配置", "🎨"),
        BackupItem("coverRule", "coverRule.json", "封面规则", "配置", "🖼️"),
        BackupItem("directLinkRule", "directLinkRule.json", "直链规则", "配置", "🔗"),
        BackupItem("homepage", "homepage.json", "首页", "数据库", "🏠"),
        BackupItem("appConfig", "config.xml", "应用配置", "配置", "⚙️"),
        BackupItem("videoConfig", "videoConfig.xml", "视频配置", "配置", "🎬"),
        BackupItem("backgroundImages", "bg", "背景图片", "其他", "🌄"),
        BackupItem("bookCache", "book_cache", "书籍缓存", "其他", "💾"),
    )

    val groups = allItems.map { it.group }.distinct()

    val groupItems: Map<String, List<BackupItem>> = allItems.groupBy { it.group }

    private val selectedMaps: MutableMap<Scope, MutableMap<String, Boolean>> = load()

    private fun load(): MutableMap<Scope, MutableMap<String, Boolean>> {
        val result = LinkedHashMap<Scope, MutableMap<String, Boolean>>()
        Scope.values().forEach { result[it] = HashMap() }
        val file = FileUtils.createFileIfNotExist(configPath)
        if (!file.exists() || file.length() <= 0) {
            return result
        }
        val json = GSON.fromJsonObject<Map<String, Any?>>(file.readText()).getOrNull() ?: return result
        var hasScopeConfig = false
        json.forEach { (key, value) ->
            val scope = Scope.fromKey(key)
            if (scope != null && value is Map<*, *>) {
                // 新版格式：按备份目标分别保存
                hasScopeConfig = true
                value.forEach { (itemKey, itemValue) ->
                    if (itemKey is String && itemValue is Boolean) {
                        result.getValue(scope)[itemKey] = itemValue
                    }
                }
            }
        }
        if (!hasScopeConfig) {
            // 旧版格式只有一份勾选结果，本地与云端都沿用
            json.forEach { (key, value) ->
                if (value is Boolean) {
                    result.values.forEach { it[key] = value }
                }
            }
        }
        return result
    }

    private fun selectionOf(scope: Scope): MutableMap<String, Boolean> =
        selectedMaps.getOrPut(scope) { HashMap() }

    fun isSelected(key: String, scope: Scope = Scope.Local): Boolean =
        selectionOf(scope)[key] ?: true

    fun getSelectedKeys(scope: Scope = Scope.Local): Set<String> = allItems
        .filter { isSelected(it.key, scope) }
        .map { it.key }
        .toSet()

    fun setSelected(key: String, selected: Boolean, scope: Scope = Scope.Local) {
        selectionOf(scope)[key] = selected
    }

    // 供选择器弹窗在确认时一次性提交内存中的完整选择结果。
    fun setSelectedKeys(keys: Set<String>, scope: Scope = Scope.Local) {
        allItems.forEach { selectionOf(scope)[it.key] = it.key in keys }
    }

    fun selectAll(scope: Scope = Scope.Local) {
        allItems.forEach { selectionOf(scope)[it.key] = true }
    }

    fun deselectAll(scope: Scope = Scope.Local) {
        allItems.forEach { selectionOf(scope)[it.key] = false }
    }

    fun getSelectedFileNames(scope: Scope = Scope.Local): List<String> =
        allItems.filter { isSelected(it.key, scope) }.map { it.fileName }

    fun isAllSelected(scope: Scope = Scope.Local): Boolean = allItems.all { isSelected(it.key, scope) }

    fun isNoneSelected(scope: Scope = Scope.Local): Boolean = allItems.none { isSelected(it.key, scope) }

    /**
     * 本地备份与云端备份的勾选结果是否完全一致。
     *
     * 一致时备份只需打包一次，本地保存与云端上传共用同一个备份包。
     */
    fun isSameSelection(): Boolean =
        getSelectedKeys(Scope.Local) == getSelectedKeys(Scope.WebDav)

    fun save() {
        val json = GSON.toJson(selectedMaps.mapKeys { it.key.key })
        FileUtils.createFileIfNotExist(configPath).writeText(json)
    }

    /**
     * 获取分组的 Emoji 图标
     */
    fun getGroupIcon(group: String): String? = when (group) {
        "数据库" -> "📊"
        "配置" -> "⚙️"
        "其他" -> "📦"
        else -> null
    }
}
