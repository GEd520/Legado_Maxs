package io.legado.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStateStore
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.ColorUtils

/**
 * Legado 自定义 Compose 主题。
 *
 * 根据当前主题配置（日间/夜间、主色、背景色等）构建 Material3 [ColorScheme]，
 * 并通过 [MaterialTheme] 提供给子组件使用；排版档位经 [rememberAppTypography] 注入，
 * 正文/标签跟随用户「界面字体」设置。
 *
 * ## 主题响应机制
 * 颜色取自 [ThemeStore] 的普通值，本身没有可观察源，因此这里读取 [ThemeStateStore.version]：
 * [io.legado.app.help.config.ThemeConfig.applyTheme] 改完色板会递增该版本号，
 * 读到它的组合立刻重组，不再依赖 Activity `recreate()` 把整棵树重建
 * （重建窗口内、重建请求被回声判定吞掉时都不会再停在旧配色）。
 *
 * ## 使用方式
 * ```kotlin
 * setLegadoContent {
 *     LegadoTheme {
 *         // 你的 Compose 内容
 *     }
 * }
 * ```
 *
 * @param content 子组件内容，可在其中通过 MaterialTheme.colorScheme 获取主题色
 */
@Composable
fun LegadoTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val isNightTheme = AppConfig.isNightTheme
    val primaryColorValue = ThemeStore.primaryColor(context)
    val accentColor = ThemeStore.accentColor(context)
    val bgColor = ThemeStore.backgroundColor(context)
    val textPrimaryColor = ThemeStore.textColorPrimary(context)
    val textSecondaryColor = ThemeStore.textColorSecondary(context)

    // 色板版本号既建立重组依赖（见类注释），也作为色板对象的缓存键：
    // 输入没变时不必每次重组都重建整个 ColorScheme
    val colorScheme = remember(
        ThemeStateStore.version,
        isNightTheme,
        primaryColorValue,
        accentColor,
        bgColor,
        textPrimaryColor,
        textSecondaryColor,
    ) {
        buildLegadoColorScheme(
            isNightTheme = isNightTheme,
            primaryColorValue = primaryColorValue,
            accentColor = accentColor,
            bgColor = bgColor,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
        )
    }

    MaterialTheme(colorScheme = colorScheme, typography = rememberAppTypography()) {
        content()
    }
}

/** 由主题配置推导 Material3 色板。非 Composable：纯计算，便于被 `remember` 缓存 */
private fun buildLegadoColorScheme(
    isNightTheme: Boolean,
    primaryColorValue: Int,
    accentColor: Int,
    bgColor: Int,
    textPrimaryColor: Int,
    textSecondaryColor: Int,
): ColorScheme {
    val isLight = !isNightTheme && ColorUtils.isColorLight(bgColor)
    val background = Color(bgColor)
    val primary = Color(accentColor)
    val secondary = Color(primaryColorValue)
    val onBackground = Color(textPrimaryColor)
    val onBackgroundVariant = Color(textSecondaryColor)

    val surface = lerp(background, if (isLight) Color.White else Color.Black, if (isLight) 0.04f else 0.10f)
    val surfaceVariant = lerp(background, onBackground, if (isLight) 0.05f else 0.14f)
    val outline = lerp(background, onBackground, if (isLight) 0.12f else 0.24f)
    val onSurfaceVariant = onBackgroundVariant

    // surfaceContainer 系列（DropdownMenu、AlertDialog 等组件的容器色），
    // 由主题背景色推导，保证这些弹层随主题背景颜色自适应，而不是 M3 默认的固定白/黑色
    val elevationTarget = if (isLight) Color.White else Color.Black
    val surfaceContainerLowest = background
    val surfaceContainerLow = lerp(background, elevationTarget, if (isLight) 0.025f else 0.06f)
    val surfaceContainer = lerp(background, elevationTarget, if (isLight) 0.05f else 0.12f)
    val surfaceContainerHigh = lerp(background, elevationTarget, if (isLight) 0.075f else 0.18f)
    val surfaceContainerHighest = lerp(background, elevationTarget, if (isLight) 0.10f else 0.24f)

    return if (isLight) {
        lightColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = secondary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = surfaceVariant,
            tertiaryContainer = surfaceVariant,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.75f),
            onPrimary = if (ColorUtils.isColorLight(accentColor)) Color.Black else Color.White,
            onSecondary = if (ColorUtils.isColorLight(primaryColorValue)) Color.Black else Color.White,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onSurfaceVariant,
            error = Color(0xFFE53935),
            onError = Color.White,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest
        )
    } else {
        darkColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = secondary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = surfaceVariant,
            tertiaryContainer = surfaceVariant,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.8f),
            onPrimary = if (ColorUtils.isColorLight(accentColor)) Color.Black else Color.White,
            onSecondary = if (ColorUtils.isColorLight(primaryColorValue)) Color.Black else Color.White,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onSurfaceVariant,
            error = Color(0xFFFF5252),
            onError = Color.Black,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest
        )
    }
}
