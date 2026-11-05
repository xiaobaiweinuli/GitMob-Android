package com.gitmob.app.ui.login

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h900dp")
class AccountAuthSheetTest {
    private val accounts = AccountSessionRule(null)
    private val composeRule = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(composeRule)

    private fun text(resource: Int) = ApplicationProvider.getApplicationContext<Context>().getString(resource)
    private val password = SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)

    @Test
    fun `PAT面板校验空输入且在途取消清空密码并可重新打开`() {
        val repository = mockk<AuthRepository>()
        val response = CompletableDeferred<ApiResult<AuthenticatedUser>>()
        var cancelled = false
        coEvery { repository.loginWithToken("test-candidate") } coAnswers {
            try { response.await() } finally { cancelled = true }
        }
        val vm = LoginViewModel(repository, ErrorEventBus(), accounts.session)
        try {
            composeRule.setContent { MaterialTheme { LoginScreen(accounts.session.epoch.id, vm) } }
            composeRule.onNodeWithText(text(R.string.accounts_pat)).performClick()
            composeRule.onNodeWithText(text(R.string.auth_pat_title)).assertIsDisplayed()
            composeRule.onNode(password).assertIsDisplayed()
            // shortcut: Robolectric 弹窗触摸未触发回调，原生触摸仍需 APK 实测。
            composeRule.onNodeWithText(text(R.string.auth_add_account)).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(R.string.login_token_required, vm.state.value.inlineErrorRes)
            composeRule.onNodeWithText(text(R.string.login_token_required)).assertIsDisplayed()
            coVerify(exactly = 0) { repository.loginWithToken(any()) }

            composeRule.onNode(password).performTextInput("test-candidate")
            composeRule.onNodeWithText(text(R.string.auth_add_account)).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.isLoading }
            composeRule.onNode(password).assertIsNotEnabled()
            composeRule.onNodeWithText(text(R.string.common_cancel)).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { cancelled && vm.state.value.authSheet == null }
            assertEquals(LoginUiState(), vm.state.value)
            composeRule.onNode(password).assertDoesNotExist()

            composeRule.onNodeWithText(text(R.string.accounts_pat)).assertIsEnabled().performClick()
            assertEquals("", composeRule.onNode(password).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            coVerify(exactly = 1) { repository.loginWithToken("test-candidate") }
        } finally {
            response.complete(ApiResult.Failure(ApiError.NetworkError))
            vm.cancelAuthorization()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `实际浏览器按钮失败发顶部事件但保留授权码和轮询`() {
        val repository = mockk<AuthRepository>()
        val waiting = DeviceFlowState.Waiting("TEST-CODE", "https://github.com/login/device", 600)
        var cancelled = false
        every { repository.authorizeDevice() } returns flow {
            try { emit(waiting); awaitCancellation() } finally { cancelled = true }
        }
        val bus = ErrorEventBus()
        val vm = LoginViewModel(repository, bus, accounts.session)
        val events = mutableListOf<BannerEvent>()
        val collector = vm.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.eventsFor(vm.accountEpoch).collect { events += it }
        }
        var opened: String? = null
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened = uri
                throw ActivityNotFoundException("No browser")
            }
        }
        try {
            vm.openDeviceFlow()
            composeRule.setContent {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    MaterialTheme { AccountAuthSheet(vm) }
                }
            }
            composeRule.onNodeWithText("TEST-CODE").assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.auth_open_browser)).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { events.isNotEmpty() }

            assertEquals(waiting.verificationUri, opened)
            assertEquals(listOf(BannerEvent.Error(ApiError.UserVisible(R.string.auth_browser_unavailable))), events)
            assertEquals(waiting, vm.state.value.deviceFlow)
            assertTrue(vm.state.value.isLoading)
            assertFalse(cancelled)
            composeRule.onNodeWithText("TEST-CODE").assertIsDisplayed()
        } finally {
            collector.cancel()
            vm.cancelAuthorization()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `过期面板按钮重新获取验证码且移除面板取消等待`() {
        val repository = mockk<AuthRepository>()
        var attempts = 0
        var cancelled = false
        every { repository.authorizeDevice() } answers {
            flow {
                if (++attempts == 1) emit(DeviceFlowState.Failed(ApiError.UserVisible(R.string.auth_device_expired)))
                else try {
                    emit(DeviceFlowState.Waiting("NEW-CODE", "https://github.com/login/device", 600))
                    awaitCancellation()
                } finally { cancelled = true }
            }
        }
        val vm = LoginViewModel(repository, ErrorEventBus(), accounts.session)
        val visible = mutableStateOf(true)
        try {
            vm.openDeviceFlow()
            composeRule.setContent { MaterialTheme { if (visible.value) AccountAuthSheet(vm) } }
            composeRule.onNodeWithText(text(R.string.auth_device_expired)).assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.auth_device_restart)).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText("NEW-CODE").assertIsDisplayed()
            assertEquals(2, attempts)

            composeRule.runOnIdle { visible.value = false }
            composeRule.waitUntil(timeoutMillis = 5_000) { cancelled && vm.state.value.authSheet == null }
            assertEquals(LoginUiState(), vm.state.value)
        } finally {
            vm.cancelAuthorization()
            vm.viewModelScope.cancel()
        }
    }
}
