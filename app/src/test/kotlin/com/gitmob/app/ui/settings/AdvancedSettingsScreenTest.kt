package com.gitmob.app.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.model.LibrarySettings
import com.gitmob.app.data.model.HistoryLimitResult
import com.gitmob.app.ui.library.LibrarySettingsState
import com.gitmob.app.ui.library.HistoryCountState
import com.gitmob.app.ui.library.LibrarySettingOperation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class AdvancedSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun historyOffHidesDependentRowsAndPendingFavoriteDoesNotDisableHistory() {
        val state = mutableStateOf(LibrarySettingsState(LibrarySettings()))
        val pending = mutableStateOf(emptySet<LibrarySettingOperation>())
        compose.setContent {
            MaterialTheme {
                AdvancedSettingsContent(state.value, HistoryCountState(12), pending.value, {}, {}, {},
                    { pending.value = setOf(LibrarySettingOperation.Favorites) },
                    { state.value = state.value.copy(value = state.value.value!!.copy(historyEnabled = it)) },
                    { _, _, _ -> }, {})
            }
        }
        val history = context.getString(R.string.history_enable)
        compose.onNodeWithText(context.getString(R.string.favorites_enable)).performClick()
        compose.onNodeWithText(history).assertIsEnabled().assertIsOn().performClick()
        compose.onNodeWithText(history).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.history_limit)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.history_clear)).assertDoesNotExist()
        compose.onNodeWithText(history).performClick()
        compose.onNodeWithText(context.getString(R.string.history_limit)).assertExists()
        compose.onNodeWithText(context.getString(R.string.history_clear)).assertExists()
    }

    // Robolectric #8460: text-field dialogs loop with custom window widths above its 320dp default.
    @Test
    @Config(qualifiers = "w320dp-h800dp")
    fun historyLimitHasOnlyNumericInputAndConfirmsTrimming() {
        var saved: Int? = null
        compose.setContent {
            MaterialTheme {
                AdvancedSettingsContent(LibrarySettingsState(LibrarySettings()), HistoryCountState(),
                    emptySet(), {}, {}, {}, {}, {}, { value, confirmed, done ->
                        if (!confirmed) done(HistoryLimitResult.NeedsConfirmation(80))
                        else { saved = value; done(HistoryLimitResult.Saved) }
                    }, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.history_limit)).performClick()
        compose.onNodeWithText("50").assertDoesNotExist()
        compose.onNodeWithText("200").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("0")
        compose.onNodeWithText(context.getString(R.string.library_save)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("40")
        compose.onNodeWithText(context.getString(R.string.library_save)).performClick()
        assertEquals(null, saved)
        compose.onNodeWithText(context.getString(R.string.history_trim_confirm, 40, 40)).assertExists()
        compose.onNodeWithText(context.getString(R.string.library_confirm)).performClick()
        assertEquals(40, saved)
    }

    @Test fun unknownSettingsShowNoInteractiveDefaultsAndCountFailureKeepsSettingsVisible() {
        val settings = mutableStateOf(LibrarySettingsState())
        val count = mutableStateOf(HistoryCountState())
        var retries = 0
        compose.setContent {
            MaterialTheme {
                AdvancedSettingsContent(settings.value, count.value, emptySet(), {}, {}, { retries++ }, {}, {}, { _, _, _ -> }, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.advanced_settings)).assertExists()
        compose.onNodeWithText(context.getString(R.string.favorites_enable)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.history_enable)).assertDoesNotExist()
        compose.runOnIdle {
            settings.value = LibrarySettingsState(LibrarySettings(favoritesEnabled = false, historyLimit = 37))
            count.value = HistoryCountState(failed = true)
        }
        compose.onNodeWithText(context.getString(R.string.favorites_enable)).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.history_enable)).assertIsOn().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.history_limit)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.history_clear)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.history_count, 0)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun settingsReadFailureKeepsSwitchesAndExposesExistingRetry() {
        val state = mutableStateOf(LibrarySettingsState(LibrarySettings(favoritesEnabled = false)))
        var retries = 0
        compose.setContent {
            MaterialTheme {
                AdvancedSettingsContent(state.value, HistoryCountState(12), emptySet(), {}, {
                    retries++
                    state.value = state.value.copy(failed = false)
                }, {}, {}, {}, { _, _, _ -> }, {})
            }
        }
        compose.runOnIdle { state.value = state.value.copy(failed = true) }
        compose.onNodeWithText(context.getString(R.string.favorites_enable)).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.history_enable)).assertIsOn().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.favorites_enable)).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.history_enable)).assertIsOn()
    }

}
