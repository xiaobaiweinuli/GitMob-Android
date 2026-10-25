package com.gitmob.app.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferenceTest {

    @Test
    fun `深浅判断同时支持系统和强制模式`() {
        assertFalse(ThemePreference(mode = ThemeMode.SYSTEM).isDarkTheme(false))
        assertTrue(ThemePreference(mode = ThemeMode.SYSTEM).isDarkTheme(true))
        assertFalse(ThemePreference(mode = ThemeMode.LIGHT).isDarkTheme(true))
        assertTrue(ThemePreference(mode = ThemeMode.DARK).isDarkTheme(false))
    }
}
