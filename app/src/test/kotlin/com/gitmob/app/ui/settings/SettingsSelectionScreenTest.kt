package com.gitmob.app.ui.settings

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gitmob.app.R
import com.gitmob.app.ui.theme.ThemePreference
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsSelectionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `language is applied once only after popup removal`() = withLanguageMock {
        setSettingsContent()
        clickSelection(R.string.settings_language)
        composeRule.mainClock.autoAdvance = false

        chooseLanguage(R.string.lang_desc_en)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNode(isPopup()).assertExists()
        verify(exactly = 0) { AppLanguage.apply(any()) }

        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 1) { AppLanguage.apply(AppLanguage.ENGLISH) }
        composeRule.mainClock.advanceTimeBy(1000)
        verify(exactly = 1) { AppLanguage.apply(any()) }
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `current language closes without applying again`() = withLanguageMock {
        setSettingsContent()
        clickSelection(R.string.settings_language)
        chooseLanguage(R.string.settings_language_system_desc)

        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 0) { AppLanguage.apply(any()) }
    }

    @Test
    fun `explicit Chinese is distinct from following system`() = withLanguageMock {
        setSettingsContent()
        clickSelection(R.string.settings_language)
        chooseLanguage(R.string.lang_desc_zh_hans)

        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 1) { AppLanguage.apply(AppLanguage.SIMPLIFIED_CHINESE) }
    }

    @Test
    fun `repeat selection during exit keeps the first language`() = withLanguageMock {
        setSettingsContent()
        clickSelection(R.string.settings_language)
        composeRule.mainClock.autoAdvance = false
        chooseLanguage(R.string.lang_desc_en)
        composeRule.mainClock.advanceTimeByFrame()
        chooseLanguage(R.string.lang_desc_zh_hans)

        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 1) { AppLanguage.apply(AppLanguage.ENGLISH) }
        verify(exactly = 1) { AppLanguage.apply(any()) }
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `outside dismissal does not apply a language`() = withLanguageMock {
        val root = openLanguageMenuRoot()
        composeRule.runOnIdle {
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try {
                root.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 0) { AppLanguage.apply(any()) }
    }

    @Test
    fun `back dismissal does not apply a language`() = withLanguageMock {
        val root = openLanguageMenuRoot()
        composeRule.runOnIdle {
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 0) { AppLanguage.apply(any()) }
    }

    @Test
    fun `leaving composition during menu exit drops pending language`() = withLanguageMock {
        val visible = mutableStateOf(true)
        setSettingsContent(visible = visible)
        clickSelection(R.string.settings_language)
        composeRule.mainClock.autoAdvance = false
        chooseLanguage(R.string.lang_desc_en)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { visible.value = false }

        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 0) { AppLanguage.apply(any()) }
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `outgoing started page drops pending language without applying on resume`() = withLanguageMock {
        val owner = TestLifecycleOwner()
        setSettingsContent(owner = owner)
        clickSelection(R.string.settings_language)
        composeRule.mainClock.autoAdvance = false
        chooseLanguage(R.string.lang_desc_en)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }

        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 0) { AppLanguage.apply(any()) }
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.mainClock.advanceTimeBy(1000)
        verify(exactly = 0) { AppLanguage.apply(any()) }
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `palette menu retains description and saves selected style once`() {
        val viewModel = mockk<SettingsViewModel>(relaxed = true)
        setAppearanceContent(viewModel)

        clickSelection(R.string.settings_palette_style)
        composeRule.onNodeWithText(context.getString(R.string.settings_palette_neutral_desc))
            .assertIsDisplayed().performClick()

        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 1) { viewModel.setPaletteStyle(PaletteStyle.Neutral) }
    }

    @Test
    fun `color specification menu saves selected specification once`() {
        val viewModel = mockk<SettingsViewModel>(relaxed = true)
        setAppearanceContent(viewModel)

        clickSelection(R.string.settings_color_spec)
        composeRule.onNodeWithText("Material 2025").assertIsDisplayed().performClick()

        composeRule.onNode(isPopup()).assertDoesNotExist()
        verify(exactly = 1) { viewModel.setColorSpec(ColorSpec.SpecVersion.SPEC_2025) }
    }

    private fun withLanguageMock(block: () -> Unit) {
        mockkObject(AppLanguage.Companion)
        try {
            every { AppLanguage.current() } returns AppLanguage.SYSTEM
            every { AppLanguage.apply(any()) } returns Unit
            block()
        } finally {
            unmockkObject(AppLanguage.Companion)
        }
    }

    private fun setSettingsContent(
        visible: State<Boolean>? = null,
        owner: LifecycleOwner = TestLifecycleOwner(),
    ) {
        val viewModel = mockk<SettingsViewModel>(relaxed = true)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    if (visible?.value != false) {
                        SettingsScreen(preference = ThemePreference(), viewModel = viewModel)
                    }
                }
            }
        }
    }

    private fun chooseLanguage(descriptionRes: Int) {
        composeRule.onNodeWithText(context.getString(descriptionRes)).performClick()
    }

    private fun openLanguageMenuRoot(): View {
        setSettingsContent()
        clickSelection(R.string.settings_language)
        val root = composeRule.onNodeWithText(context.getString(R.string.lang_desc_en))
            .fetchSemanticsNode().root
        return (root as View).rootView
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun setAppearanceContent(viewModel: SettingsViewModel) {
        composeRule.setContent {
            MaterialTheme {
                AppearanceScreen(preference = ThemePreference(), onBack = {}, viewModel = viewModel)
            }
        }
    }

    private fun clickSelection(titleRes: Int) {
        val selection = hasText(context.getString(titleRes)) and hasClickAction()
        composeRule.onNode(
            hasScrollToNodeAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).performScrollToNode(selection)
        composeRule.onNode(selection).performClick()
        composeRule.waitForIdle()
    }
}
