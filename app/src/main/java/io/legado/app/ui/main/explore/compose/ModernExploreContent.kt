package io.legado.app.ui.main.explore.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.BookShelfState
import io.legado.app.ui.book.explore.compose.EXPLORE_LAYOUT_GRID
import io.legado.app.ui.book.explore.compose.EXPLORE_LAYOUT_LIST
import io.legado.app.ui.book.explore.compose.EXPLORE_LAYOUT_WATERFALL
import io.legado.app.ui.book.explore.compose.ExploreShowActions
import io.legado.app.ui.book.explore.compose.ExploreShowController
import io.legado.app.ui.book.explore.compose.ExploreShowListContent
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors
import io.legado.app.ui.widget.components.ModernTagBar
import io.legado.app.ui.widget.components.dialog.AppSearchableChoiceDialog
import io.legado.app.ui.widget.components.dialog.AppTagGridDialog
import io.legado.app.ui.widget.components.BlockProgressChip
import io.legado.app.ui.widget.components.BookBottomSheet

/** 标签条里的一个分类项（参考分支 DiscoverTagItem 的精简版） */
private data class ModernTagItem(
    val text: String,
    val url: String?,
    val group: String?,
    val kind: ExploreKind,
)

/** 标签/分组展开弹窗的数量阈值（对齐参考分支 ExpandableTagSelector.EXPAND_THRESHOLD） */
private const val TAG_EXPAND_THRESHOLD = 12

/**
 * 新版发现：在发现主 Tab 内直接显示"源切换 + 分类标签 + 内容列表"，
 * 免去"书源列表 → 分类列表"的两级跳转（对齐 Legado_R 新版发现的结构）。
 *
 * 结构对齐参考分支：
 * - 头部行在 TitleBar 正下方左上角：源名（20sp 粗体）+ ▾ 下拉切换源；
 *   右侧是"发现页管理"（齿轮）与功能菜单（三点）两个圆钮；
 * - 分类按整行项拆成大分组：分组条（可切换分组）+ 当前分组的 url 类标签条
 *   （每分组自动带「全部」项），标签条末尾 ▾ 展开全部标签；
 * - select/text/button 类不进标签条，收进「发现页管理」表单弹窗：
 *   select 值写入书源 infoMap 并触发分类重建，与旧版展开分类区同一套机制。
 *
 * @param controller 内容区状态（与独立发现列表页同一套控制器）
 * @param kindsController 分类控制器（JS 求值 / infoMap / 内联 WebView，宿主 Fragment 持有）
 * @param onSwitchLegacy 切换回旧版发现
 */
@Composable
fun ModernExploreContent(
    controller: ExploreShowController,
    actions: ExploreShowActions,
    kindsController: ExploreKindsController,
    sources: List<BookSourcePart>,
    selectedSourceUrl: String?,
    onSelectSource: (BookSourcePart) -> Unit,
    onSwitchLegacy: () -> Unit,
    showBlockProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showBookSheet by remember { mutableStateOf(false) }
    var sheetBook by remember { mutableStateOf<SearchBook?>(null) }
    var sheetShelfState by remember { mutableStateOf(BookShelfState.NOT_IN_SHELF) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }

    // 分类区状态：随选中源重建；书源规则刷新信号（长按刷新/登录/筛选回调）变化时重建
    val kindsState = remember(selectedSourceUrl) { ExploreKindsState() }
    val refreshTick = selectedSourceUrl?.let { kindsController.refreshTick(it) } ?: 0
    var handledRefreshTick by remember(selectedSourceUrl) { mutableStateOf(0) }
    // 当前选中的标签 URL（筛选重建后据此恢复）
    var selectedTagUrl by remember(selectedSourceUrl) { mutableStateOf<String?>(null) }
    // 当前大分组（随选中源重置）
    var currentGroup by remember(selectedSourceUrl) { mutableStateOf<String?>(null) }

    val allLabel = stringResource(R.string.all)
    val otherLabel = stringResource(R.string.other)

    LaunchedEffect(selectedSourceUrl, refreshTick) {
        val sourceUrl = selectedSourceUrl ?: return@LaunchedEffect
        val force = refreshTick != handledRefreshTick
        handledRefreshTick = refreshTick
        kindsState.load(kindsController, sourceUrl, refreshTick)
        if (force) {
            // 筛选变化后分类已被书源重建：恢复原选中分类，失效则落到第一个 url 类
            // （只认 type==url：select/button/text/toggle 的 url 是模板/脚本，不能当分类加载）
            val items = buildModernTagItems(kindsState.kinds, allLabel, otherLabel)
            val target = items.firstOrNull {
                it.url == selectedTagUrl && it.url != null && it.kind.type == ExploreKind.Type.url
            } ?: items.firstOrNull { it.url != null && it.kind.type == ExploreKind.Type.url }
            target?.let {
                selectedTagUrl = it.url
                controller.loadExploreUrl(it.url.orEmpty(), it.text)
            }
        }
    }

    // 首次进入时控制器已自动选中第一个 url 类分类：
    // 把控制器当前分类同步到标签条选中态，避免"加载了分类但标签无高亮"
    LaunchedEffect(controller.currentExploreUrl) {
        val url = controller.currentExploreUrl ?: return@LaunchedEffect
        if (selectedTagUrl == null) {
            selectedTagUrl = url
        }
    }

    // 标签条只放可直接加载的 url 类分类：select/button/text/toggle 的 url 是模板/脚本，
    // 进表单（发现页管理）而非标签条（对齐参考分支 tagItems = filter { type != select && !isButton }）
    val allItems = remember(kindsState.kinds, allLabel, otherLabel) {
        buildModernTagItems(kindsState.kinds, allLabel, otherLabel)
    }
    val groups = remember(allItems) { allItems.mapNotNull { it.group }.distinct() }
    val currentGroupValue = groups.firstOrNull { it == currentGroup } ?: groups.firstOrNull()
    val tagItems = remember(allItems, currentGroupValue) {
        if (currentGroupValue == null) {
            allItems.filter { it.url != null && it.kind.type == ExploreKind.Type.url }
        } else {
            allItems.filter {
                it.group == currentGroupValue && it.url != null && it.kind.type == ExploreKind.Type.url
            }
        }
    }
    val settingItems = remember(allItems, groups) {
        buildModernSettingItems(allItems, groups.isNotEmpty())
    }

    // 切换大分组（对齐参考分支 rvDiscoverSelects 点击 → applyDiscoverTagFilterAndSelect）：
    // 当前选中标签不属于新分组时，自动选中并加载新分组的第一个 url 类标签，
    // 否则只切分组条、内容区还停在旧分组（起点按钮筛选这类多分组源上必现）
    fun selectGroup(group: String?) {
        currentGroup = group
        val groupTags = allItems.filter {
            it.group == group && it.url != null && it.kind.type == ExploreKind.Type.url
        }
        if (groupTags.none { it.url == selectedTagUrl }) {
            val target = groupTags.firstOrNull()
            selectedTagUrl = target?.url
            if (target != null) {
                controller.loadExploreUrl(target.url.orEmpty(), target.text)
            } else {
                // 分组下没有可选分类（如按钮筛选组）：清空内容区显示空态，
                // 不保留上一个分组的内容（对齐参考分支 clearDiscoverBooksToEmpty）
                controller.clearBooksToEmpty()
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        ModernExploreHeader(
            controller = controller,
            actions = actions,
            onSwitchLegacy = onSwitchLegacy,
            sources = sources,
            selectedSourceUrl = selectedSourceUrl,
            hasSettings = settingItems.isNotEmpty(),
            onPickSource = { showSourcePicker = true },
            onOpenSettings = { showSettingsSheet = true },
            modifier = Modifier.fillMaxWidth()
        )
        // 大分组条：仅当书源声明了整行分组项时显示
        if (currentGroupValue != null) {
            ModernTagBar(
                items = groups,
                selectedIndex = groups.indexOf(currentGroupValue),
                onSelect = { index -> selectGroup(groups.getOrNull(index)) },
                onExpand = { showGroupPicker = true },
                showExpand = groups.size >= TAG_EXPAND_THRESHOLD
            )
        }
        // 当前分组的 url 类标签条
        ModernTagBar(
            items = tagItems.map { it.text },
            selectedIndex = tagItems.indexOfFirst { it.url == selectedTagUrl },
            onSelect = { index ->
                val item = tagItems.getOrNull(index) ?: return@ModernTagBar
                item.url?.let {
                    selectedTagUrl = it
                    controller.loadExploreUrl(it, item.text)
                }
            },
            onExpand = { showTagPicker = true },
            showExpand = tagItems.size >= TAG_EXPAND_THRESHOLD
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            ExploreShowListContent(
                controller = controller,
                onShowBookInfo = actions.onShowBookInfo,
                onBookLongClick = { book ->
                    sheetBook = book
                    sheetShelfState = controller.getBookShelfState(book)
                    showBookSheet = true
                }
            )
            if (showBlockProgress && controller.blockedCount > 0) {
                BlockProgressChip(
                    blockedCount = controller.blockedCount,
                    onClick = actions.onShowBlockRuleClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            start = AppDimens.exploreShowBlockChipOuterHorizontal,
                            top = AppDimens.exploreShowBlockChipOuterTop,
                            end = AppDimens.exploreShowBlockChipOuterHorizontal
                        )
                )
            }
        }
    }

    if (showSourcePicker) {
        AppSearchableChoiceDialog(
            title = stringResource(R.string.discovery),
            options = sources.map { it.bookSourceName },
            selectedIndex = sources.indexOfFirst { it.bookSourceUrl == selectedSourceUrl },
            onSelect = { index ->
                showSourcePicker = false
                sources.getOrNull(index)?.let(onSelectSource)
            },
            onDismissRequest = { showSourcePicker = false }
        )
    }

    if (showTagPicker) {
        AppTagGridDialog(
            title = stringResource(R.string.select),
            options = tagItems.map { it.text },
            selectedIndex = tagItems.indexOfFirst { it.url == selectedTagUrl },
            onSelect = { index ->
                showTagPicker = false
                tagItems.getOrNull(index)?.let { item ->
                    item.url?.let {
                        selectedTagUrl = it
                        controller.loadExploreUrl(it, item.text)
                    }
                }
            },
            onDismissRequest = { showTagPicker = false }
        )
    }

    if (showGroupPicker) {
        AppTagGridDialog(
            title = stringResource(R.string.select),
            options = groups,
            selectedIndex = groups.indexOf(currentGroupValue),
            onSelect = { index ->
                showGroupPicker = false
                selectGroup(groups.getOrNull(index))
            },
            onDismissRequest = { showGroupPicker = false }
        )
    }

    if (showSettingsSheet && selectedSourceUrl != null) {
        ModernExploreSettingsSheet(
            sourceUrl = selectedSourceUrl,
            items = settingItems,
            kindsController = kindsController,
            onOpenExplore = { url, title ->
                showSettingsSheet = false
                selectedTagUrl = url
                controller.loadExploreUrl(url, title)
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showBookSheet) {
        BookBottomSheet(
            show = true,
            book = sheetBook,
            shelfState = sheetShelfState,
            onDismiss = { showBookSheet = false },
            onAddToShelf = { actions.addToShelf(it) },
            onShowInfo = { actions.onShowBookInfo(it) }
        )
    }
}

/**
 * 头部行：左上角"源名 ▾"（20sp 粗体，点击切换源），右侧"发现页管理"（齿轮）
 * 与功能菜单（三点，Compose DropdownMenu 锚定按钮）圆钮——对齐参考分支
 * ll_discover_source_row。
 */
@Composable
private fun ModernExploreHeader(
    controller: ExploreShowController,
    actions: ExploreShowActions,
    onSwitchLegacy: () -> Unit,
    sources: List<BookSourcePart>,
    selectedSourceUrl: String?,
    hasSettings: Boolean,
    onPickSource: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentName = sources
        .firstOrNull { it.bookSourceUrl == selectedSourceUrl }
        ?.bookSourceName
        ?: sources.firstOrNull()?.bookSourceName
        ?: stringResource(R.string.discovery)
    var showMoreMenu by remember { mutableStateOf(false) }
    // 头部行顶替旧版 TitleBar，配色必须继续走 TopBarConfig 统一体系
    val topBarColors = pageTopBarColors()

    Row(
        modifier = modifier
            // 背景先于 statusBarsPadding：覆盖状态栏 + 顶栏区域，与旧版 TitleBar 一致
            .pageTopBarBackground(topBarColors)
            .statusBarsPadding()
            // 自占旧版 TitleBar 的高度（状态栏 inset + 56dp toolbar），内容垂直居中
            .height(AppDimens.topBarHeight)
            .padding(horizontal = AppDimens.exploreShowTabsHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onPickSource),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentName,
                color = topBarColors.contentColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(R.string.discovery),
                tint = topBarColors.contentColor,
                modifier = Modifier.size(28.dp)
            )
        }
        if (hasSettings) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.setting),
                    tint = topBarColors.contentColor
                )
            }
        }
        Box {
            IconButton(onClick = { showMoreMenu = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.more),
                    tint = topBarColors.contentColor
                )
            }
            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.menu_page, controller.currentPage)) },
                    onClick = {
                        showMoreMenu = false
                        actions.onPagePick(controller.currentPage) { controller.skipPageTo(it) }
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.refresh)) },
                    onClick = {
                        showMoreMenu = false
                        controller.refreshCurrent()
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.add_all_to_shelf)) },
                    onClick = {
                        showMoreMenu = false
                        actions.onAddAllToShelfClick()
                    }
                )
                DropdownMenuItem(
                    text = {
                        val modeName = when (controller.layoutMode) {
                            EXPLORE_LAYOUT_GRID -> stringResource(R.string.switch_layout_grid)
                            EXPLORE_LAYOUT_WATERFALL -> stringResource(R.string.switch_layout_waterfall)
                            else -> stringResource(R.string.switch_layout_list)
                        }
                        Text(text = stringResource(R.string.switch_layout_current, modeName))
                    },
                    onClick = {
                        showMoreMenu = false
                        controller.switchLayout()
                    }
                )
                // 列表布局没有列数概念，不显示选列入口
                if (controller.layoutMode != EXPLORE_LAYOUT_LIST) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.select_column_count)) },
                        onClick = {
                            showMoreMenu = false
                            actions.onColumnPick(controller.effectiveColumnCount()) {
                                controller.selectColumnCount(it)
                            }
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.explore_block_rule)) },
                    onClick = {
                        showMoreMenu = false
                        actions.onShowBlockRuleClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.switch_to_old_explore)) },
                    onClick = {
                        showMoreMenu = false
                        onSwitchLegacy()
                    }
                )
            }
        }
    }
}

/**
 * 「发现页管理」表单弹窗（对齐参考分支 RowUiDialog）：
 * select/text/button 类分类项以流式表单呈现，select 选中即关闭并重建分类。
 *
 * 每项宽度对齐参考分支 RowUiForm.createRowLayoutParams：
 * 声明 flexBasisPercent 的按整行宽百分比、声明 flexGrow 的按内容宽再分剩余空间、
 * 两者都没声明的独占整行（View 版 MATCH_PARENT）——与分类区共用 ExploreFlexLayout。
 */
@Composable
private fun ModernExploreSettingsSheet(
    sourceUrl: String,
    items: List<ModernTagItem>,
    kindsController: ExploreKindsController,
    onOpenExplore: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kindsActions = remember(sourceUrl) {
        ExploreSourceActions(
            onToggleExpand = {},
            onMenuAction = { _, _ -> },
            onOpenExplore = { _, title, url -> onOpenExplore(url, title) },
            onShowError = {},
            onShowPhoto = { _, _ -> },
        )
    }
    // 表单任一项（select/toggle/text）改值后分类可能已被书源重建：
    // 统一在弹窗关闭路径上触发刷新信号，不能靠各组件自行回调（会漏）
    var formChanged by remember { mutableStateOf(false) }
    fun dismissWithRefresh() {
        if (formChanged) {
            kindsController.requestRefresh(sourceUrl)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { dismissWithRefresh() },
        title = { Text(text = stringResource(R.string.setting)) },
        text = {
            ExploreFlexLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                items = items.map { item ->
                    val style = item.kind.style()
                    ExploreFlexItemSpec(
                        style = style,
                        fillLine = style.layout_flexBasisPercent < 0f && style.layout_flexGrow <= 0f
                    )
                },
                horizontalSpacing = AppDimens.exploreKindSpacing,
                verticalSpacing = AppDimens.exploreKindSpacing,
            ) { index ->
                val item = items[index]
                ExploreKindItem(
                    kind = item.kind,
                    sourceUrl = sourceUrl,
                    controller = kindsController,
                    actions = kindsActions,
                    onSelected = if (item.kind.type == ExploreKind.Type.select) {
                        {
                            // select 选中即关闭（对齐参考分支 dismissOnSelect），
                            // 关闭前必须标记变更，否则刷新信号在关闭路径上丢失
                            formChanged = true
                            dismissWithRefresh()
                        }
                    } else {
                        null
                    },
                    onFormChanged = { formChanged = true },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { dismissWithRefresh() }) {
                Text(text = stringResource(R.string.confirm))
            }
        },
        dismissButton = {}
    )
}

/**
 * 分类项分组（对齐参考分支 buildDiscoverTagItems）：
 * - 整行类（flexBasisPercent>=0.95 或 flexGrow>=1）且无 action 的项作为分组标题，
 *   其后的项归入该分组；分组标题自带 url 时补一个「全部」项；
 * - url 类（非 button/select）进标签条；select/button/text 进设置表单；
 * - 无分组时全部归「其它」。
 */
private fun buildModernTagItems(
    kinds: List<ExploreKind>,
    allLabel: String,
    otherLabel: String,
): List<ModernTagItem> {
    var currentGroup: String? = null
    val result = mutableListOf<ModernTagItem>()
    kinds.forEach { kind ->
        val action = kind.action?.takeIf { it.isNotBlank() }
        val url = kind.url?.takeIf { it.isNotBlank() }
        val isSelect = kind.type == ExploreKind.Type.select
        val isButton = kind.type == ExploreKind.Type.button && !action.isNullOrBlank()

        if (isModernMajorGroupKind(kind, currentGroup != null)) {
            currentGroup = kind.title.trim().ifBlank { null }
            if (!url.isNullOrBlank()) {
                result += ModernTagItem(
                    text = allLabel,
                    url = url,
                    group = currentGroup,
                    kind = kind
                )
            }
            return@forEach
        }

        if (!url.isNullOrBlank() && !isButton && !isSelect) {
            result += ModernTagItem(
                text = kind.title,
                url = url,
                group = currentGroup,
                kind = kind
            )
            return@forEach
        }

        if (isSelect || isButton || kind.type == ExploreKind.Type.text ||
            kind.type == ExploreKind.Type.toggle || !action.isNullOrBlank()
        ) {
            result += ModernTagItem(
                text = kind.title,
                url = url,
                group = currentGroup,
                kind = kind
            )
        }
    }
    val hasGroup = result.any { it.group != null }
    return if (hasGroup) {
        result
    } else {
        result.map { it.copy(group = otherLabel) }
    }.distinctBy { "${it.group}|${it.kind.type}|${it.kind.title}|${it.kind.url}|${it.kind.action}" }
}

/** 整行项判定（对齐参考分支 isDiscoverMajorGroupKind / isDiscoverFullLineKind） */
private fun isModernMajorGroupKind(kind: ExploreKind, hasStartedGroup: Boolean): Boolean {
    if (!kind.action.isNullOrBlank()) return false
    if (kind.type == ExploreKind.Type.button || kind.type == ExploreKind.Type.select) return false
    if (!kind.url.isNullOrBlank() && !hasStartedGroup) return false
    val style = kind.style()
    if (style.layout_flexBasisPercent >= 0.95f) return true
    if (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f) return true
    return false
}

/** 设置表单内容：select / text / button（含 action）类 */
private fun buildModernSettingItems(
    items: List<ModernTagItem>,
    hasGroups: Boolean,
): List<ModernTagItem> {
    return items.filter {
        it.kind.type == ExploreKind.Type.select ||
            it.kind.type == ExploreKind.Type.text ||
            it.kind.type == ExploreKind.Type.toggle ||
            (it.kind.type == ExploreKind.Type.button && !it.kind.action.isNullOrBlank()) ||
            (hasGroups && it.group == null && it.url != null)
    }
}
