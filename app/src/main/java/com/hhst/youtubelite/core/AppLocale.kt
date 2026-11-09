package com.hhst.youtubelite.core

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.core.os.LocaleListCompat

/**
 * API 33+ delegates to the system's per-app locales; older versions wrap activity contexts
 * with the persisted BCP-47 tag. An empty tag always means "follow the system".
 */
object AppLocale {
    /** Tags must exist as packaged resource locales (see localeFilters). */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "" to "System default",
        "en" to "English",
        "zh-Hans" to "简体中文",
        "zh-Hant" to "繁體中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "de" to "Deutsch",
        "fr" to "Français",
        "hi" to "हिन्दी",
        "ru" to "Русский",
        "ar" to "العربية",
        "es" to "Español",
        "pt" to "Português",
        "bg" to "Български",
        "bn" to "বাংলা",
        "bs" to "Bosanski",
        "cs" to "Čeština",
        "da" to "Dansk",
        "el" to "Ελληνικά",
        "fa" to "فارسی",
        "fi" to "Suomi",
        "fil" to "Filipino",
        "gu" to "ગુજરાતી",
        "he" to "עברית",
        "hr" to "Hrvatski",
        "hu" to "Magyar",
        "id" to "Bahasa Indonesia",
        "it" to "Italiano",
        "kn" to "ಕನ್ನಡ",
        "ml" to "മലയാളം",
        "mr" to "मराठी",
        "ms" to "Bahasa Melayu",
        "nb" to "Norsk (bokmål)",
        "nl" to "Nederlands",
        "pa" to "ਪੰਜਾਬੀ",
        "pl" to "Polski",
        "ro" to "Română",
        "sk" to "Slovenčina",
        "sl" to "Slovenščina",
        "sr" to "Српски",
        "sv" to "Svenska",
        "ta" to "தமிழ்",
        "te" to "తెలుగు",
        "th" to "ไทย",
        "tr" to "Türkçe",
        "uk" to "Українська",
        "ur" to "اردو",
        "vi" to "Tiếng Việt",
    )

    fun tag(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        } else AppSettings.language(context)

    /** Applies the persisted tag; true when the system-level path was used. */
    fun apply(context: Context, tag: String): Boolean {
        AppSettings.setLanguage(context, tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return false
            manager.applicationLocales =
                if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return true
        }
        return false
    }

    /** Wraps a base context with the persisted locale on API levels the OS does not manage. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = AppSettings.language(base)
        if (tag.isBlank()) return base
        val locales = LocaleListCompat.forLanguageTags(tag)
        if (locales.isEmpty) return base
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocales(locales.toLanguageTags().let(LocaleList::forLanguageTags))
        return base.createConfigurationContext(config)
    }
}

/** Boot-time synchronous reads for values needed before DI is up. */
internal object AppSettings {
    private const val KEY_LANGUAGE = "preferences:app_language"

    /** Same prefs file the extension settings store writes; safe before Koin starts. */
    fun language(context: Context): String =
        PreferenceStore.app(context).readString(KEY_LANGUAGE).orEmpty()

    fun setLanguage(context: Context, tag: String) {
        PreferenceStore.app(context).putString(KEY_LANGUAGE, tag)
    }
}
