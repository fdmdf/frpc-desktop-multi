package com.frpc.android.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.frpc.android.data.AppLanguage
import java.util.Locale

/**
 * 应用内语言切换。
 *
 * 不走 AppCompatDelegate：那会拖进 appcompat 一整棵依赖树（fragment / emoji2 / customview …），
 * 而这里只需要「给 Activity 换一个 Configuration」。所以直接用 `createConfigurationContext`
 * 在 `attachBaseContext` 阶段换掉语言，切换后 `recreate()` 即可生效。
 */
object LocaleHelper {

    /** 把语言标签转成 LocaleList；SYSTEM 交还系统当前列表 */
    fun localeListOf(language: String): LocaleList = when (language) {
        AppLanguage.ZH -> LocaleList(Locale.SIMPLIFIED_CHINESE)
        AppLanguage.EN -> LocaleList(Locale.ENGLISH)
        else -> LocaleList.getDefault()
    }

    /**
     * 生成带目标语言的 Context，供 `Activity.attachBaseContext` 使用。
     * 必须在 super.attachBaseContext 之前调用，否则资源已经按旧语言加载完了。
     */
    fun apply(context: Context, language: String): Context {
        val locales = localeListOf(language)
        Locale.setDefault(locales[0])
        val config = Configuration(context.resources.configuration)
        config.setLocales(locales)
        return context.createConfigurationContext(config)
    }
}
