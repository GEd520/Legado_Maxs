package io.legado.app.ui.main.rss.compose

import androidx.compose.runtime.Stable

/**
 * 订阅页列表需要向宿主（Fragment）抛出的动作集合。
 *
 * 集中成一个契约对象，避免每个条目透传三个 lambda；这些动作都会开 Activity、
 * 弹 View 对话框或写数据库，属于平台操作，统一留在 Fragment 执行（state-events.md §4.1）。
 *
 * 由 Fragment 在 `by lazy` 里构造一次，属性不再变化，因此标注 [Stable] 是成立的。
 */
@Stable
internal class RssSourceActions(
    /** 点击订阅源：按 singleUrl / startHtml 规则打开阅读或分类页 */
    val onOpen: (RssSourceItem) -> Unit,
    /** 长按菜单选中某项 */
    val onMenuAction: (RssSourceItem, RssSourceMenuAction) -> Unit,
    /** 点击网格首格的"规则订阅"入口 */
    val onOpenRuleSub: () -> Unit,
)
