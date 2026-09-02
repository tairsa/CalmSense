package com.example.app.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The app's display language, chosen in Settings rather than followed from the
 * system.
 *
 * Backed by AppCompatDelegate's per-app locales, which is the platform feature
 * on Android 13+ and a backport below it. Two things follow from using it
 * rather than a hand-rolled Context wrapper:
 *
 *  - The choice survives restart without us persisting anything. On API 33+
 *    the system stores it; below that appcompat does, via the
 *    androidx.appcompat.app.AppLocalesMetadataHolderService entry in the
 *    manifest. Storing it again in SettingsStore would just create a second
 *    source of truth that can disagree.
 *  - On Android 13+ the same choice appears in Settings > Apps > CalmSense >
 *    Language, so the OS and the app never contradict each other.
 *
 * Hebrew is right-to-left. Compose mirrors layout automatically from the
 * locale, and the manifest already declares supportsRtl, so no per-screen work
 * is needed - but anything using hardcoded Left/Right instead of Start/End
 * would not mirror, which is worth watching for in review.
 */
object LanguageManager {

    /** A language the user can pick. [tag] is a BCP-47 tag for LocaleListCompat. */
    enum class Language(val tag: String, val englishName: String, val nativeName: String) {
        /** Follow whatever the device is set to. */
        SYSTEM("", "System default", "ברירת מחדל"),
        ENGLISH("en", "English", "English"),
        HEBREW("he", "Hebrew", "עברית"),
    }

    /** The language currently in effect. */
    fun current(): Language {
        val tags = AppCompatDelegate.getApplicationLocales()
        if (tags.isEmpty) return Language.SYSTEM
        // Compare on the language subtag only: the stored value may carry a
        // region ("he-IL") that we never set but the system can add.
        val lang = tags[0]?.language ?: return Language.SYSTEM
        return Language.entries.firstOrNull { it.tag.isNotEmpty() && it.tag == lang }
            ?: Language.SYSTEM
    }

    /**
     * Switch language. Safe to call from a composable callback: appcompat
     * recreates the affected activities itself, so callers do not need to.
     */
    fun set(language: Language) {
        AppCompatDelegate.setApplicationLocales(
            if (language == Language.SYSTEM) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language.tag)
        )
    }
}
