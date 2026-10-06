package io.legado.app.lib.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 主题色板的可观察版本号。
 *
 * Compose 侧读到的颜色（[ThemeStore] 的色板、`ui/theme/LegadoTheme`、`ui/theme/CommonPageColors`）
 * 都是普通值，没有任何可观察源。过去只能靠 Activity `recreate()` 让整棵 Compose 树重建来刷新，
 * 于是「色板已变、重建还没落地」的时间窗里界面会停在旧配色（跟随系统翻转、重建请求被回声判定
 * 吞掉、不订阅 RECREATE 的页面都会踩到）。
 *
 * 把这个版本号做成 Compose state，[io.legado.app.help.config.ThemeConfig.applyTheme] 改完色板就
 * `version++`，读到它的组合会立刻重组，与重建窗口解耦。
 */
object ThemeStateStore {

    /** 当前色板版本；读取它会建立 Compose 重组依赖 */
    var version by mutableIntStateOf(0)
        private set

    /** 色板已更新，通知所有读取 [version] 的组合重新取值 */
    fun notifyThemeChanged() {
        version += 1
    }
}
