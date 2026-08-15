package com.gitmob.app.ui.settings

import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `SYSTEM 对应空 LocaleList`() {
        assertTrue(AppLanguage.SYSTEM.toLocaleList().isEmpty)
        assertEquals(
            AppLanguage.SYSTEM,
            AppLanguage.fromLocaleList(LocaleListCompat.getEmptyLocaleList()),
        )
    }

    @Test
    fun `三种语言 tag 与 LocaleList 双向映射`() {
        for (language in listOf(
            AppLanguage.SIMPLIFIED_CHINESE,
            AppLanguage.TRADITIONAL_CHINESE,
            AppLanguage.ENGLISH,
        )) {
            assertEquals(language, AppLanguage.fromLocaleList(language.toLocaleList()))
        }
    }

    @Test
    fun `繁体变体归入繁体档`() {
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-TW")) {
            assertEquals(
                "tag=$tag",
                AppLanguage.TRADITIONAL_CHINESE,
                AppLanguage.fromLocaleList(LocaleListCompat.forLanguageTags(tag)),
            )
        }
    }

    @Test
    fun `简体与地区变体归入简体档`() {
        for (tag in listOf("zh-CN", "zh", "zh-Hans", "zh-Hans-CN", "zh-SG")) {
            assertEquals(
                "tag=$tag",
                AppLanguage.SIMPLIFIED_CHINESE,
                AppLanguage.fromLocaleList(LocaleListCompat.forLanguageTags(tag)),
            )
        }
    }

    @Test
    fun `英语与未知语言的归档`() {
        assertEquals(
            AppLanguage.ENGLISH,
            AppLanguage.fromLocaleList(LocaleListCompat.forLanguageTags("en-US")),
        )
        // 不认识的语言（理论上不会出现，防御性归为跟随系统）
        assertEquals(
            AppLanguage.SYSTEM,
            AppLanguage.fromLocaleList(LocaleListCompat.forLanguageTags("fr")),
        )
    }
}
