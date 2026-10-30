package com.frpc.android.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.frpc.android.data.AppLanguage
import java.util.Locale

object LocaleHelper {

    fun localeListOf(language: String): LocaleList = when (language) {
        AppLanguage.ZH -> LocaleList(Locale.SIMPLIFIED_CHINESE)
        AppLanguage.EN -> LocaleList(Locale.ENGLISH)
        else -> LocaleList.getDefault()
    }

    fun apply(context: Context, language: String): Context {
        val locales = localeListOf(language)
        Locale.setDefault(locales[0])
        val config = Configuration(context.resources.configuration)
        config.setLocales(locales)
        return context.createConfigurationContext(config)
    }
}
