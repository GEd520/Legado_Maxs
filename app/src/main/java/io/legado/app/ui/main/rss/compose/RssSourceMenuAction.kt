package io.legado.app.ui.main.rss.compose

/**
 * 订阅页条目长按菜单的动作。
 *
 * Compose 侧只负责"用户点了哪一项"，编辑、置顶、登录、删除等行为仍由 Fragment
 * 执行——这些行为要开 Activity、弹 View 对话框，属于平台操作，不下沉到 Compose。
 * 菜单项与 [io.legado.app.R.menu.rss_main_item] 一一对应。
 */
enum class RssSourceMenuAction {
    Edit,
    ToTop,
    Login,
    Disable,
    Delete,
}
