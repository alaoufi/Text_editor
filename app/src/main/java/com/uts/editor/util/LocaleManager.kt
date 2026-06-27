package com.uts.editor.util

import android.content.Context
import android.content.res.Configuration
import com.uts.editor.data.AppLanguage
import java.util.Locale

/**
 * Runtime in-app language switching without reinstalling. The chosen tag is
 * stored in synchronous SharedPreferences so it can be applied in
 * [android.app.Activity.attachBaseContext], before any UI is created.
 */
object LocaleManager {

    private const val PREFS = "uts_locale"
    private const val KEY = "lang"

    fun storedLanguage(context: Context): AppLanguage {
        val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return when (tag) {
            "ar" -> AppLanguage.ARABIC
            "en" -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.tag ?: "system")
            .apply()
    }

    /** Wraps [base] with the stored locale; call from attachBaseContext. */
    fun wrap(base: Context): Context {
        val language = storedLanguage(base)
        val tag = language.tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
