package com.gitmob.app.ui.login

import android.content.ClipboardManager
import android.os.SystemClock
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h900dp")
class AccountAuthSheetTest {
    private val accounts = AccountSessionRule(null)
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
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
            composeRule.onNodeWithText(text(R.string.login_pat_action)).performClick()
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
            composeRule.onNodeWithText(text(R.string.auth_create_classic_pat)).assertIsNotEnabled()
            composeRule.onNodeWithText(text(R.string.common_cancel)).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { cancelled && vm.state.value.authSheet == null }
            assertEquals(LoginUiState(), vm.state.value)
            composeRule.onNode(password).assertDoesNotExist()

            composeRule.onNodeWithText(text(R.string.login_pat_action)).assertIsEnabled().performClick()
            assertEquals("", composeRule.onNode(password).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            coVerify(exactly = 1) { repository.loginWithToken("test-candidate") }
        } finally {
            response.complete(ApiResult.Failure(ApiError.NetworkError))
            vm.cancelAuthorization()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `经典PAT入口预填权限并限定外部浏览器失败时保留输入`() {
        val repository = mockk<AuthRepository>()
        val bus = ErrorEventBus()
        val vm = LoginViewModel(repository, bus, accounts.session)
        val events = mutableListOf<BannerEvent>()
        val collector = vm.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.eventsFor(vm.accountEpoch).collect { events += it }
        }
        val intents = mutableListOf<Intent>()
        var fail = true
        val browserContext = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) {
                intents += intent
                if (fail) throw ActivityNotFoundException("No browser")
            }
        }
        try {
            vm.openPat()
            vm.onTokenInputChange("test-candidate")
            composeRule.setContent {
                CompositionLocalProvider(LocalContext provides browserContext) {
                    MaterialTheme { AccountAuthSheet(vm) }
                }
            }
            composeRule.onNodeWithText(text(R.string.auth_create_classic_pat)).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { events.isNotEmpty() }
            val intent = intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertTrue(intent.selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
            assertEquals("https", intent.data!!.scheme)
            assertEquals("github.com", intent.data!!.host)
            assertEquals("/settings/tokens/new", intent.data!!.path)
            assertEquals("GitMob", intent.data!!.getQueryParameter("description"))
            assertEquals(setOf("repo", "user", "gist", "read:org", "notifications", "workflow", "delete_repo", "write:discussion"), intent.data!!.getQueryParameter("scopes")!!.split(',').toSet())
            assertEquals(listOf(BannerEvent.Error(ApiError.UserVisible(R.string.deep_link_browser_unavailable))), events)
            assertEquals("test-candidate", vm.state.value.tokenInput)
            assertEquals(AuthSheet.PAT, vm.state.value.authSheet)
            coVerify(exactly = 0) { repository.loginWithToken(any()) }

            fail = false
            composeRule.onNodeWithText(text(R.string.auth_create_classic_pat)).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(2, intents.size)
            assertEquals("test-candidate", vm.state.value.tokenInput)
        } finally {
            collector.cancel()
            vm.cancelAuthorization()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `短屏幕大字号可以滚动至PAT入口并打开面板`() {
        val vm = LoginViewModel(mockk(), ErrorEventBus(), accounts.session)
        try {
            composeRule.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    MaterialTheme { LoginScreen(accounts.session.epoch.id, vm) }
                }
            }
            composeRule.onNodeWithText(text(R.string.login_pat_action)).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(AuthSheet.PAT, vm.state.value.authSheet)
        } finally {
            vm.cancelAuthorization()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `授权按钮限定浏览器且打开失败保留授权码和轮询`() {
        val repository = mockk<AuthRepository>()
        val waiting = DeviceFlowState.Waiting("TEST-CODE", "https://github.com/login/device", SystemClock.elapsedRealtime() + 600_000)
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
        val intents = mutableListOf<Intent>()
        var failure: RuntimeException? = ActivityNotFoundException("No browser")
        val browserContext = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) {
                intents += intent
                failure?.let { throw it }
            }
        }
        try {
            vm.openDeviceFlow()
            composeRule.setContent {
                CompositionLocalProvider(LocalContext provides browserContext) {
                    MaterialTheme { AccountAuthSheet(vm) }
                }
            }
            composeRule.onNodeWithText("TEST-CODE").assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.auth_open_browser)).assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { events.isNotEmpty() }

            val intent = intents.single()
            assertEquals(waiting.verificationUri, intent.dataString)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertEquals(Intent.ACTION_MAIN, intent.selector?.action)
            assertTrue(intent.selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
            assertEquals(listOf(BannerEvent.Error(ApiError.UserVisible(R.string.auth_browser_unavailable))), events)

            failure = SecurityException("Blocked browser")
            composeRule.onNodeWithText(text(R.string.auth_open_browser)).performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(timeoutMillis = 5_000) { events.size == 2 }
            failure = null
            composeRule.onNodeWithText(text(R.string.auth_open_browser)).performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(3, intents.size)
            assertEquals(2, events.size)
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
    fun `点击授权码复制完整内容并显示反馈再次获取时重置`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val repository = mockk<AuthRepository>()
        var attempts = 0
        every { repository.authorizeDevice() } answers {
            flow {
                emit(DeviceFlowState.Waiting("CODE-${++attempts}", "https://github.com/login/device", SystemClock.elapsedRealtime() + 600_000))
                awaitCancellation()
            }
        }
        val vm = LoginViewModel(repository, ErrorEventBus(), accounts.session)
        try {
            vm.openDeviceFlow()
            composeRule.setContent { MaterialTheme { AccountAuthSheet(vm) } }
            composeRule.onNodeWithText("CODE-1").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            assertEquals("CODE-1", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(1, clipboard.primaryClip!!.itemCount)
            composeRule.onNodeWithText(text(R.string.auth_device_copied)).assertIsDisplayed()
            composeRule.runOnIdle { vm.cancelAuthorization() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { vm.openDeviceFlow() }
            composeRule.onNodeWithText("CODE-2").assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.auth_device_copied)).assertDoesNotExist()
            composeRule.onNodeWithText("CODE-2").performSemanticsAction(SemanticsActions.OnClick)
            assertEquals("CODE-2", clipboard.primaryClip!!.getItemAt(0).text.toString())
        } finally {
            vm.cancelAuthorization()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `没有新的轮询响应时逐秒倒计时且返回前台重新计算到期时间`() {
        val repository = mockk<AuthRepository>()
        val deadline = SystemClock.elapsedRealtime() + 600_000L
        val waiting = DeviceFlowState.Waiting("TEST-CODE", "https://github.com/login/device", deadline)
        every { repository.authorizeDevice() } returns flow { emit(waiting); awaitCancellation() }
        val vm = LoginViewModel(repository, ErrorEventBus(), accounts.session)
        fun seconds() = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
        fun countdown(value: Long) = ApplicationProvider.getApplicationContext<Context>().getString(R.string.auth_device_remaining, value)
        try {
            vm.openDeviceFlow()
            composeRule.setContent { MaterialTheme { AccountAuthSheet(vm) } }
            composeRule.waitForIdle()
            composeRule.mainClock.autoAdvance = false
            val before = seconds()
            composeRule.onNodeWithText(countdown(before)).assertIsDisplayed()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onNodeWithText(countdown(seconds())).assertIsDisplayed()
            assertEquals(before - 1, seconds())

            composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            composeRule.mainClock.advanceTimeByFrame()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            // 先执行生命周期恢复任务，再提交重组；不等待下一秒的定时刷新。
            composeRule.mainClock.advanceTimeBy(32)
            composeRule.onNodeWithText(countdown(seconds())).assertIsDisplayed()
            assertEquals(waiting, vm.state.value.deviceFlow)

            ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onNodeWithText(countdown(0)).assertIsDisplayed()
            composeRule.onNodeWithText("TEST-CODE").assertIsNotEnabled()
            composeRule.onNodeWithText(text(R.string.auth_open_browser)).assertIsNotEnabled()
        } finally {
            composeRule.mainClock.autoAdvance = true
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
                    emit(DeviceFlowState.Waiting("NEW-CODE", "https://github.com/login/device", SystemClock.elapsedRealtime() + 600_000))
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

    @Test
    fun `PAT仅401标记输入错误而操作失败单独展示`() {
        val repository = mockk<AuthRepository>()
        val cases = listOf(
            ApiError.Unauthorized to R.string.auth_token_invalid,
            ApiError.NetworkError to R.string.error_network,
            ApiError.RateLimited to R.string.error_rate_limited,
            ApiError.UserVisible(R.string.auth_storage_write_failed) to R.string.auth_storage_write_failed,
        )
        val vm = LoginViewModel(repository, ErrorEventBus(), accounts.session)
        try {
            vm.openPat()
            composeRule.setContent { MaterialTheme { AccountAuthSheet(vm) } }
            for ((error, resource) in cases) {
                coEvery { repository.loginWithToken("candidate") } returns ApiResult.Failure(error)
                composeRule.runOnIdle { vm.onTokenInputChange("candidate") }
                composeRule.onNodeWithText(text(R.string.auth_add_account)).performSemanticsAction(SemanticsActions.OnClick)
                composeRule.onNodeWithText(text(resource)).assertIsDisplayed()
                val hasFieldError = composeRule.onNode(password).fetchSemanticsNode().config.contains(SemanticsProperties.Error)
                assertEquals(error == ApiError.Unauthorized, hasFieldError)
                assertEquals(if (error == ApiError.Unauthorized) resource else null, vm.state.value.inlineErrorRes)
                assertEquals(if (error == ApiError.Unauthorized) null else resource, vm.state.value.operationErrorRes)
            }
        } finally { vm.cancelAuthorization(); vm.viewModelScope.cancel() }
    }

}
