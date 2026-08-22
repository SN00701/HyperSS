package com.hyperss.app.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** 应用级语言切换：把 Settings.language 应用到 Context 资源。 */
object LocaleHelper {

    /** 根据偏好返回目标 Locale（system 时跟随系统）。 */
    fun targetLocale(): Locale = when (Settings.language) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> Locale.getDefault()
    }

    /** 包装 context，使其资源使用目标语言。 */
    fun applyLocale(context: Context): Context {
        val locale = when (Settings.readLanguageFrom(context)) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}