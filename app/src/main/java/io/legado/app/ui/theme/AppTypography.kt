package io.legado.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import io.legado.app.help.config.AppConfig

/**
 * 应用排版档位，经 [LegadoTheme] 注入 `MaterialTheme.typography`（规范见 theme-styles.md §7.4）。
 *
 * 字号与权重沿用 Material3 默认档位，本项目不另设字号体系。这里只织入一件事：
 * 用户「界面字体」设置（[AppConfig.systemTypefaces]，0 默认 / 1 衬线 / 2 等宽）——
 * body / label 档位跟随用户字体；title / headline / display 保持系统默认，
 * 与 View 侧 [io.legado.app.lib.theme.applyUiBodyTypefaceDeep] 跳过标题角色同语义。
 *
 * 设置没有可观察源：改设置后靠主题应用/页面重建带来的重组读到新值，
 * 与 View 侧需重新遍历 View 树生效是同一限制。
 */
@Composable
fun rememberAppTypography(): Typography {
    val bodyFontFamily = when (AppConfig.systemTypefaces) {
        1 -> FontFamily.Serif
        2 -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    return remember(bodyFontFamily) {
        val m3 = Typography()
        m3.copy(
            bodyLarge = m3.bodyLarge.copy(fontFamily = bodyFontFamily),
            bodyMedium = m3.bodyMedium.copy(fontFamily = bodyFontFamily),
            bodySmall = m3.bodySmall.copy(fontFamily = bodyFontFamily),
            labelLarge = m3.labelLarge.copy(fontFamily = bodyFontFamily),
            labelMedium = m3.labelMedium.copy(fontFamily = bodyFontFamily),
            labelSmall = m3.labelSmall.copy(fontFamily = bodyFontFamily),
        )
    }
}
