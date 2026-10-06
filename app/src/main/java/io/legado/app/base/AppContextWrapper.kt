package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.sysConfiguration
import java.util.*


@Suppress("unused")
object AppContextWrapper {

    @SuppressLint("ObsoleteSdkInt")
    fun wrap(context: Context): Context {
        val resources: Resources = context.resources
        val configuration: Configuration = resources.configuration
        val targetLocale = getSetLocale(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(targetLocale)
            configuration.setLocales(LocaleList(targetLocale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = targetLocale
        }
        configuration.fontScale = getFontScale(context)
        return context.createConfigurationContext(configuration)
    }

    fun getFontScale(context: Context): Float {
        var fontScale = context.getPrefInt(PreferKey.fontScale) / 10f
        if (fontScale !in 0.8f..1.6f) {
            fontScale = sysConfiguration.fontScale
        }
        return fontScale
    }

    /**
     * 把系统新送来的配置同步到「被包装过」的 Context 资源上。
     *
     * [wrap] 用 [Context.createConfigurationContext] 产出的 Context 会把**当时那份** Configuration
     * （含 `uiMode` 昼夜位）固化成快照，之后系统配置变化不会再更新它。而 AppCompat 解析
     * `AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM`、以及给 Activity 资源套层次时，读的正是这份快照：
     * 系统夜切后它算出与系统相反的模式，反过来把 Activity 的资源按旧昼夜罩住，
     * 表现为「背景/调色板跟随了、字体颜色没跟随」（浅底浅字 / 黑底黑字）。
     *
     * 这里把新配置（并回我们自己的语言、字号覆盖）打回资源，让快照跟着系统走。
     */
    @Suppress("DEPRECATION")
    fun syncConfiguration(context: Context, configuration: Configuration) {
        val resources = context.resources
        val merged = Configuration(configuration).apply {
            val targetLocale = getSetLocale(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setLocales(LocaleList(targetLocale))
            } else {
                @Suppress("DEPRECATION")
                locale = targetLocale
            }
            fontScale = getFontScale(context)
        }
        runCatching {
            resources.updateConfiguration(merged, resources.displayMetrics)
        }
    }

    /**
     * 当前系统语言
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun getSystemLocale(): Locale {
        val locale: Locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { //7.0有多语言设置获取顶部的语言
            locale = sysConfiguration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            locale = sysConfiguration.locale
        }
        return locale
    }

    /**
     * 当前App语言
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun getAppLocale(context: Context): Locale {
        val locale: Locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locale = context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            locale = context.resources.configuration.locale
        }
        return locale

    }

    /**
     * 当前设置语言
     */
    private fun getSetLocale(context: Context): Locale {
        return when (context.getPrefString(PreferKey.language)) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "tw" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.ENGLISH
            else -> getSystemLocale()
        }
    }

    /**
     * 判断App语言和设置语言是否相同
     */
    fun isSameWithSetting(context: Context): Boolean {
        val locale = getAppLocale(context)
        val language = locale.language
        val country = locale.country
        val pfLocale = getSetLocale(context)
        val pfLanguage = pfLocale.language
        val pfCountry = pfLocale.country
        return language == pfLanguage && country == pfCountry
    }

}