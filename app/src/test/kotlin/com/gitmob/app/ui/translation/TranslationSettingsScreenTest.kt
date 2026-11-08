package com.gitmob.app.ui.translation

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.translation.TranslationConfiguration
import com.gitmob.app.data.translation.TranslationPreferences
import com.gitmob.app.data.translation.TranslationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class TranslationSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val state = MutableStateFlow(TranslationUiState())
    private val viewModel = mockk<TranslationViewModel>(relaxed = true).also {
        every { it.state } returns state
    }

    private fun text(id: Int) = context.getString(id)

    @Test fun advancedEntryDistinguishesLoadingOffEnabledAndStorageFailure() {
        var navigations = 0
        compose.setContent { MaterialTheme { AdvancedTranslationEntry({ navigations++ }, viewModel) } }
        compose.onNodeWithText(text(R.string.translation_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.translation_off)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.translation_title)).performClick()
        assertEquals(1, navigations)

        compose.runOnIdle { state.value = TranslationUiState(configuration = TranslationConfiguration()) }
        compose.onNodeWithText(text(R.string.translation_off)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.translation_loading)).assertDoesNotExist()
        compose.runOnIdle {
            state.value = TranslationUiState(configuration = TranslationConfiguration(TranslationPreferences(enabled = true, target = "ja")))
        }
        compose.onNodeWithText("Microsoft Edge · 日本語").assertIsDisplayed()
        compose.runOnIdle { state.value = TranslationUiState(configuration = TranslationConfiguration(storageFailed = true)) }
        compose.onNodeWithText(text(R.string.translation_error_storage)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.translation_off)).assertDoesNotExist()
    }

    @Test fun settingsWaitForConfigurationThenShowTheSavedSwitchState() {
        compose.setContent { MaterialTheme { TranslationSettingsScreen({}, viewModel) } }
        compose.onNodeWithText(text(R.string.translation_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.translation_enable)).assertDoesNotExist()
        compose.runOnIdle { state.value = TranslationUiState(configuration = TranslationConfiguration()) }
        compose.onNodeWithText(text(R.string.translation_loading)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.translation_enable)).assertIsOff().performClick()
        verify(exactly = 1) { viewModel.savePreferences(TranslationPreferences(enabled = true)) }
        compose.onNodeWithText(text(R.string.translation_options)).assertDoesNotExist()
    }

    @Test fun unreadableStorageDisablesEditingAndShowsItsOwnError() {
        state.value = TranslationUiState(configuration = TranslationConfiguration(storageFailed = true))
        compose.setContent { MaterialTheme { TranslationSettingsScreen({}, viewModel) } }
        compose.onNodeWithText(text(R.string.translation_error_storage)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.translation_enable)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.translation_provider)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.translation_test_hint)).assertDoesNotExist()
        verify(exactly = 0) { viewModel.savePreferences(any()) }
        compose.onNodeWithText(text(R.string.common_retry)).performClick()
        verify(exactly = 1) { viewModel.retryConfiguration() }
    }

    @Test fun providerSelectionStillWorksAfterScrollingTheIndependentSections() {
        val preferences = TranslationPreferences(enabled = true, target = "ja", bilingual = false)
        state.value = TranslationUiState(configuration = TranslationConfiguration(preferences))
        compose.setContent { MaterialTheme { TranslationSettingsScreen({}, viewModel) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.translation_anonymous_hint)))
        compose.onNodeWithText(text(R.string.translation_anonymous_hint)).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Microsoft Edge"))
        compose.onNodeWithText("Microsoft Edge").performClick()
        // ModalBottomSheet's Robolectric window may be outside physical touch bounds.
        compose.onNodeWithText("Tencent Transmart").performSemanticsAction(SemanticsActions.OnClick) { it() }
        verify(exactly = 1) { viewModel.savePreferences(preferences.copy(provider = TranslationProvider.TRANSMART)) }
    }

    @Test fun splitOptionsKeepOtherPreferencesWhenTargetLanguageChanges() {
        val preferences = TranslationPreferences(enabled = true, provider = TranslationProvider.CUSTOM, source = "auto", bilingual = false)
        state.value = TranslationUiState(configuration = TranslationConfiguration(preferences))
        compose.setContent { MaterialTheme { TranslationSettingsScreen({}, viewModel) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.translation_source)))
        compose.onNodeWithText(text(R.string.translation_source)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.translation_target)).performScrollTo().performClick()
        compose.onNodeWithText("日本語").performScrollTo().performClick()
        verify(exactly = 1) { viewModel.savePreferences(preferences.copy(target = "ja")) }
    }
}
