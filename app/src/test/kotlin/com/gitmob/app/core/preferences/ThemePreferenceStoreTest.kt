package com.gitmob.app.core.preferences

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.ui.theme.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemePreferenceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ThemePreferenceStore(context)

    @Test
    fun `全部主题偏好能够持久化`() = runTest {
        val blue = Color(0xFF1A73E8)

        store.setMode(ThemeMode.DARK)
        store.setUseDynamicColor(true)
        store.setCustomSeedColor(blue)
        store.setUseAmoled(true)
        store.setPaletteStyle(PaletteStyle.Expressive)
        store.setColorSpec(ColorSpec.SpecVersion.SPEC_2025)
        store.setEnablePredictiveBack(false)
        store.setPageScale(0.9f)

        val preference = store.preference.first()
        assertEquals(ThemeMode.DARK, preference.mode)
        assertEquals(true, preference.useDynamicColor)
        assertEquals(blue, preference.customSeedColor)
        assertEquals(true, preference.useAmoled)
        assertEquals(PaletteStyle.Expressive, preference.paletteStyle)
        assertEquals(ColorSpec.SpecVersion.SPEC_2025, preference.colorSpec)
        assertFalse(preference.enablePredictiveBack)
        assertEquals(0.9f, preference.pageScale)
    }

    @Test
    fun `选择品牌色会原子关闭动态取色并清除自定义种子色`() = runTest {
        store.setUseDynamicColor(true)
        store.setCustomSeedColor(Color(0xFF1A73E8))

        store.selectCustomSeedColor(null)

        val preference = store.preference.first()
        assertFalse(preference.useDynamicColor)
        assertNull(preference.customSeedColor)
    }

    @Test
    fun `页面缩放写入时限制在安全范围`() = runTest {
        store.setPageScale(2f)
        assertEquals(1.1f, store.preference.first().pageScale)

        store.setPageScale(0.2f)
        assertEquals(0.8f, store.preference.first().pageScale)
    }
}
