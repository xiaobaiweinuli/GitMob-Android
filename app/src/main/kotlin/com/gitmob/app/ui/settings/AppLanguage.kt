package com.gitmob.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用内语言选项。切换/持久化全部走 AppCompatDelegate（官方 per-app language 路线）：
 *   - Android 13+ 由系统存储并与"系统设置 → 应用语言"双向同步；
 *   - API ≤32 由 appcompat 的 autoStoreLocales（AndroidManifest 里的
 *     AppLocalesMetadataHolderService）自动持久化并在冷启动恢复。
 * 本项目自己不落任何语言键（不进 DataStore，也不碰 SharedPreferences 红线）。
 */
enum class AppLanguage(val tag: String?) {
    /** 跟随系统语言（清空应用级 locale 覆盖） */
    SYSTEM(null),
    SIMPLIFIED_CHINESE("zh-CN"),
    TRADITIONAL_CHINESE("zh-TW"),
    ENGLISH("en"),
    ;

    fun toLocaleList(): LocaleListCompat =
        if (tag == null) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)

    companion object {
        /** 从 AppCompatDelegate 当前生效的应用级 locale 反解出选项；无覆盖 = 跟随系统。 */
        fun fromLocaleList(locales: LocaleListCompat): AppLanguage {
            if (locales.isEmpty) return SYSTEM
            val locale = locales[0] ?: return SYSTEM
            return when {
                locale.language == "en" -> ENGLISH
                locale.language == "zh" && isTraditional(locale.toLanguageTag()) -> TRADITIONAL_CHINESE
                locale.language == "zh" -> SIMPLIFIED_CHINESE
                else -> SYSTEM
            }
        }

        fun current(): AppLanguage = fromLocaleList(AppCompatDelegate.getApplicationLocales())

        /** 应用选择：setApplicationLocales 会自动触发 Activity 重建/配置变更并持久化。 */
        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(language.toLocaleList())
        }

        /** zh-TW / zh-HK / zh-Hant-* 都归入繁体档 */
        private fun isTraditional(tag: String): Boolean =
            tag.contains("Hant", ignoreCase = true) ||
                tag.endsWith("-TW", ignoreCase = true) ||
                tag.endsWith("-HK", ignoreCase = true) ||
                tag.endsWith("-MO", ignoreCase = true)
    }
}
