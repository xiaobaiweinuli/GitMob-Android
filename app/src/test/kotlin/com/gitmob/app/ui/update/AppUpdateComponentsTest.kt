package com.gitmob.app.ui.update

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.BuildConfig
import com.gitmob.app.R
import com.gitmob.app.data.model.AppUpdate
import com.gitmob.app.data.model.AppUpdatePreferences
import com.gitmob.app.ui.library.HistoryCountState
import com.gitmob.app.ui.library.LibrarySettingsState
import com.gitmob.app.ui.settings.AdvancedSettingsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class AppUpdateComponentsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun manualCheckWorksWithAutomaticOffAndProgressIsLocal() {
        val state = mutableStateOf(AppUpdateState(preferences = AppUpdatePreferences(automatic = false)))
        var checks = 0
        compose.setContent { MaterialTheme {
            AppUpdateSettingsContent(state.value, {}, {
                checks++
                state.value = state.value.copy(checking = true)
            }, {})
        } }
        val automatic = context.getString(R.string.app_update_automatic)
        compose.onNodeWithText(automatic).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.app_update_check)).assertIsEnabled().performClick()
        compose.onNodeWithText(automatic).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.app_update_checking)).assertExists()
        compose.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        assertEquals(1, checks)
    }

    @Test fun updateSectionDoesNotWaitForLibrarySettings() {
        compose.setContent { MaterialTheme {
            AdvancedSettingsContent(LibrarySettingsState(), HistoryCountState(), emptySet(),
                {}, {}, {}, {}, {}, { _, _, _ -> }, {},
                updateContent = { AppUpdateSettingsContent(AppUpdateState(preferences = AppUpdatePreferences()), {}, {}, {}) })
        } }
        compose.onNodeWithText(context.getString(R.string.app_update_check)).performScrollTo().assertIsEnabled()
    }

    @Test fun unknownPreferencesHaveNoInteractiveDefaultSwitch() {
        compose.setContent { MaterialTheme {
            AppUpdateSettingsContent(AppUpdateState(), {}, {}, {})
        } }
        compose.onNodeWithText(context.getString(R.string.app_update_automatic)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.app_update_check)).assertIsEnabled()
    }

    @Test fun dialogShowsReleaseTitleAndVersionWithOnlyIgnoreAndDownload() {
        var ignored = 0
        var downloaded = 0
        compose.setContent { MaterialTheme {
            AppUpdateDialog(AppUpdate("0.2.0", 2, "fix(auth/nav): 修复登录 401", "", "https://github.com/app.apk", 1234),
                false, {}, { ignored++ }, { downloaded++ })
        } }
        compose.onNodeWithText(context.getString(R.string.app_update_version_change, BuildConfig.VERSION_NAME, "0.2.0")).assertIsDisplayed()
        compose.onNodeWithText("fix(auth/nav): 修复登录 401").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.app_update_no_notes)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.app_update_ignore)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.app_update_download)).assertIsDisplayed().performClick()
        assertEquals(1, ignored)
        assertEquals(1, downloaded)
    }

    @Test fun pendingIgnoreDisablesBothDialogActions() {
        compose.setContent { MaterialTheme {
            AppUpdateDialog(AppUpdate("0.2.0", 2, "", "", "https://github.com/app.apk", 1234), true, {}, {}, {})
        } }
        compose.onNodeWithText(context.getString(R.string.app_update_ignore)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.app_update_download)).assertIsNotEnabled()
    }
}
