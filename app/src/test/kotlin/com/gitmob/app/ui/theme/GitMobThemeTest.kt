package com.gitmob.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitMobThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `theme changes expose target colors without waiting for interpolation`() {
        var preference by mutableStateOf(ThemePreference(mode = ThemeMode.LIGHT))
        val updates = mutableListOf<ColorScheme>()
        composeRule.setContent {
            GitMobTheme(preference) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { updates += scheme }
            }
        }
        val target = preference.copy(
            mode = ThemeMode.DARK,
            customSeedColor = Color(0xFF2196F3),
            useAmoled = true,
            paletteStyle = PaletteStyle.Vibrant,
            colorSpec = ColorSpec.SpecVersion.SPEC_2025,
        )

        composeRule.runOnIdle {
            updates.clear()
            preference = target
        }
        composeRule.waitForIdle()

        val expected = dynamicColorScheme(
            seedColor = requireNotNull(target.customSeedColor),
            isDark = true,
            isAmoled = true,
            style = target.paletteStyle,
            specVersion = target.colorSpec,
        )
        composeRule.runOnIdle {
            assertFalse(updates.isEmpty())
            // Any interpolated theme published during the update would fail this assertion.
            updates.forEach { assertEquals(expected.toString(), it.toString()) }
        }
    }

    @Test
    fun `unrelated preference change retains density instance and scale change replaces it`() {
        var preference by mutableStateOf(ThemePreference(mode = ThemeMode.LIGHT))
        lateinit var observed: Density
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                GitMobTheme(preference) {
                    val density = LocalDensity.current
                    SideEffect { observed = density }
                }
            }
        }
        lateinit var initial: Density
        composeRule.runOnIdle {
            initial = observed
            preference = preference.copy(customSeedColor = Color(0xFFF44336))
        }
        composeRule.runOnIdle { assertSame(initial, observed) }

        composeRule.runOnIdle { preference = preference.copy(pageScale = 0.9f) }

        composeRule.runOnIdle {
            assertNotSame(initial, observed)
            assertEquals(1.8f, observed.density, 0.0001f)
            assertEquals(1.3f, observed.fontScale, 0.0001f)
        }
    }

    @Test
    fun `system density and font scale changes invalidate remembered density`() {
        var systemDensity by mutableStateOf<Density>(Density(2f, 1f))
        lateinit var observed: Density
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides systemDensity) {
                GitMobTheme(ThemePreference(pageScale = 0.8f)) {
                    val density = LocalDensity.current
                    SideEffect { observed = density }
                }
            }
        }

        composeRule.runOnIdle { systemDensity = Density(3f, 1.5f) }

        composeRule.runOnIdle {
            assertEquals(2.4f, observed.density, 0.0001f)
            assertEquals(1.5f, observed.fontScale, 0.0001f)
        }
    }

    @Test
    fun `page scale stays clamped without changing system font scale`() {
        var preference by mutableStateOf(ThemePreference(pageScale = 2f))
        lateinit var observed: Density
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                GitMobTheme(preference) {
                    val density = LocalDensity.current
                    SideEffect { observed = density }
                }
            }
        }
        composeRule.runOnIdle { assertEquals(2.2f, observed.density, 0.0001f) }

        composeRule.runOnIdle { preference = preference.copy(pageScale = 0.4f) }

        composeRule.runOnIdle {
            assertEquals(1.6f, observed.density, 0.0001f)
            assertEquals(1.3f, observed.fontScale, 0.0001f)
        }
    }
}
