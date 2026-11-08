package com.gitmob.app.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.ui.login.LoginViewModel
import com.gitmob.app.ui.theme.ThemePreference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h900dp")
class SettingsAccountAuthTest {
    private val first = GitHubAccount("1", 1, "U_1", "first")
    private val second = GitHubAccount("2", 2, "U_2", "second")
    private val accounts = AccountSessionRule(first)
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(composeRule)
    private fun text(res: Int) = ApplicationProvider.getApplicationContext<Context>().getString(res)

    @Test
    fun `设置页切换失败可见并复用PAT和设备授权错误展示`() {
        accounts.session.updateAccounts(listOf(first, second))
        val auth = mockk<AuthRepository>()
        coEvery { auth.switchAccount("2") } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { auth.loginWithToken("candidate") } returns ApiResult.Failure(ApiError.UserVisible(R.string.auth_storage_write_failed))
        every { auth.authorizeDevice() } returns flowOf(DeviceFlowState.Failed(ApiError.UserVisible(R.string.auth_device_denied)))
        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)
        val settingsVm = SettingsViewModel(mockk(), auth, ErrorEventBus(), accounts.session)
        try {
            composeRule.setContent { MaterialTheme { SettingsScreen(ThemePreference(), viewModel = settingsVm, accountViewModel = vm) } }
            composeRule.onNodeWithText("@first").performClick()
            composeRule.onNodeWithText("@second").performScrollTo().performClick()
            composeRule.onNodeWithText(text(R.string.error_network)).performScrollTo().assertIsDisplayed()
            assertNull(vm.state.value.inlineErrorRes)
            assertEquals(first, accounts.session.state.value.activeAccount)

            // 从设置页打开实际共享面板，检查保存失败不会标记 PAT 输入。
            val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            composeRule.onNode(button.and(hasText(text(R.string.accounts_pat)))).performScrollTo().performClick()
            val password = SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)
            composeRule.onNode(password).performTextInput("candidate")
            composeRule.onNodeWithText(text(R.string.auth_add_account)).performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText(text(R.string.auth_storage_write_failed)).assertIsDisplayed()
            assertNull(vm.state.value.inlineErrorRes)
            composeRule.onNodeWithText(text(R.string.common_cancel)).performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNode(button.and(hasText(text(R.string.accounts_device_flow)))).performScrollTo().performClick()
            composeRule.onNodeWithText(text(R.string.auth_device_denied)).assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.auth_device_restart)).assertIsDisplayed()
            assertNull(vm.state.value.inlineErrorRes)
        } finally { vm.cancelAuthorization(); vm.viewModelScope.cancel(); settingsVm.viewModelScope.cancel() }
    }
}
