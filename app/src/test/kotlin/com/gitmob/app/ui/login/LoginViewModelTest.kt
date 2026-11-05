package com.gitmob.app.ui.login

import com.gitmob.app.R
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.model.DeviceFlowStatus
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 纯 JVM 单元测试（直接跑 ./gradlew testDebugUnitTest，
 * 不需要模拟器/真机/APK 安装，几秒内完成）。
 *
 * 符合 SKILL.md 第七节「测试规范」ViewModel 测试范式：
 *   - 测试工具：JUnit4 + kotlinx-coroutines-test + MockK
 *   - 必带 Rule：MainDispatcherRule（解决 viewModelScope.launch
 *     在纯 JVM 环境下 Dispatchers.Main 不可用的问题）
 *   - 注入方式：Fake/Mock Repository，不通过 Hilt，直接 new ViewModel(...)
 *
 * 覆盖场景：
 *   - 空 token 即时校验（inlineError）
 *   - 登录成功：loginSucceeded=true，loading 复位
 *   - 登录失败：错误同时发 ErrorEventBus + inlineError，状态回滚
 *   - 失败恢复：失败后输入正确 token 再次登录成功
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule val accounts = AccountSessionRule(null)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeViewerProfile() = AuthenticatedUser(1, "U_1", "me", "me", null)

    @Test
    fun `空token点击登录时显示inline错误且不发网络请求`() = runTest {
        val auth = mockk<AuthRepository>(relaxed = true)
        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)

        vm.onTokenInputChange("   ")
        vm.login()

        assertEquals(R.string.login_token_required, vm.state.value.inlineErrorRes)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loginSucceeded)
    }

    @Test
    fun `登录在途时不重复提交或改写输入`() = runTest {
        val auth = mockk<AuthRepository>()
        val response = CompletableDeferred<ApiResult<AuthenticatedUser>>()
        coEvery { auth.loginWithToken("candidate") } coAnswers { response.await() }
        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)
        try {
            vm.onTokenInputChange("candidate")
            vm.login()
            assertTrue(vm.state.value.isLoading)
            vm.login()
            vm.onTokenInputChange("other")
            assertEquals("candidate", vm.state.value.tokenInput)
            coVerify(exactly = 1) { auth.loginWithToken("candidate") }
            response.complete(ApiResult.Success(fakeViewerProfile()))
            assertEquals("", vm.state.value.tokenInput)
            assertTrue(vm.state.value.loginSucceeded)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `登录成功时loading复位且loginSucceeded为true`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.loginWithToken("ghp_validtoken") } returns ApiResult.Success(fakeViewerProfile())

        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)
        vm.onTokenInputChange("ghp_validtoken")
        vm.login()

        assertTrue(vm.state.value.loginSucceeded)
        assertEquals("", vm.state.value.tokenInput)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.inlineErrorRes)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `登录失败时错误同时发总线和inlineError状态回滚`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.loginWithToken("ghp_badtoken") } returns ApiResult.Failure(ApiError.Unauthorized)
        val bus = ErrorEventBus()
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.eventsFor(accounts.session.epoch).collect { events += it }
        }

        val vm = LoginViewModel(auth, bus, accounts.session)
        vm.onTokenInputChange("ghp_badtoken")
        vm.login()
        runCurrent()

        assertNotNull(vm.state.value.inlineErrorRes)
        assertEquals(listOf(BannerEvent.Error(ApiError.Unauthorized)), events)
        assertFalse(vm.state.value.loginSucceeded)
        assertFalse(vm.state.value.isLoading)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `失败恢复场景_登录失败后再输入正确token再次登录成功`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.loginWithToken("bad") } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { auth.loginWithToken("good") } returns ApiResult.Success(fakeViewerProfile())

        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)

        // 第一次：错误token
        vm.onTokenInputChange("bad")
        vm.login()
        assertFalse(vm.state.value.loginSucceeded)
        assertNotNull(vm.state.value.inlineErrorRes)

        // 第二次：改token为正确
        vm.onTokenInputChange("good")
        vm.login()
        assertTrue(vm.state.value.loginSucceeded)
        assertNull(vm.state.value.inlineErrorRes)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `取消PAT清空秘密且迟到成功或失败不能覆盖新面板`() = runTest {
        for (result in listOf(ApiResult.Success(fakeViewerProfile()), ApiResult.Failure(ApiError.Unauthorized))) {
            val auth = mockk<AuthRepository>()
            val response = CompletableDeferred<ApiResult<AuthenticatedUser>>()
            coEvery { auth.loginWithToken("old-candidate") } coAnswers { withContext(NonCancellable) { response.await() } }
            val bus = ErrorEventBus()
            val events = mutableListOf<BannerEvent>()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                bus.eventsFor(accounts.session.epoch).collect { events += it }
            }
            val vm = LoginViewModel(auth, bus, accounts.session)
            try {
                vm.openPat()
                vm.onTokenInputChange("old-candidate")
                vm.login()
                vm.cancelAuthorization()
                assertEquals(LoginUiState(), vm.state.value)
                vm.openPat()
                vm.onTokenInputChange("new-candidate")

                response.complete(result)
                runCurrent()

                assertEquals(LoginUiState(tokenInput = "new-candidate", authSheet = AuthSheet.PAT), vm.state.value)
                assertTrue(events.isEmpty())
            } finally {
                response.complete(result)
                vm.viewModelScope.cancel()
                collector.cancel()
            }
        }
    }

    @Test
    fun `DeviceFlow失败可重试且关闭等待面板取消轮询`() = runTest {
        val auth = mockk<AuthRepository>()
        var attempts = 0
        var cancelled = false
        val waiting = DeviceFlowState.Waiting("ABCD-EFGH", "https://github.com/login/device", 600, DeviceFlowStatus.SLOW_DOWN)
        every { auth.authorizeDevice() } answers {
            flow {
                attempts++
                emit(DeviceFlowState.Requesting)
                if (attempts == 1) {
                    emit(DeviceFlowState.Failed(ApiError.UserVisible(R.string.auth_device_expired)))
                } else {
                    try {
                        emit(waiting)
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        }
        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)
        try {
            vm.openDeviceFlow()
            assertFalse(vm.state.value.isLoading)
            assertEquals(R.string.auth_device_expired, vm.state.value.inlineErrorRes)
            vm.retryDeviceFlow()
            assertTrue(vm.state.value.isLoading)
            assertEquals(waiting, vm.state.value.deviceFlow)
            vm.retryDeviceFlow()
            vm.openPat()
            assertEquals(2, attempts)
            assertEquals(AuthSheet.DEVICE_FLOW, vm.state.value.authSheet)

            vm.cancelAuthorization()

            assertTrue(cancelled)
            assertEquals(LoginUiState(), vm.state.value)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `切换失败保留账号可重试且重复点击不重复请求`() = runTest {
        val first = GitHubAccount("1", 1, "U_1", "first")
        val second = GitHubAccount("2", 2, "U_2", "second")
        accounts.session.replace(listOf(first, second), first.key)
        val started = accounts.session.epoch
        val auth = mockk<AuthRepository>()
        val response = CompletableDeferred<ApiResult<Unit>>()
        coEvery { auth.switchAccount(second.key) } coAnswers { response.await() }
        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)
        try {
            vm.switchAccount("missing")
            vm.switchAccount(first.key)
            coVerify(exactly = 0) { auth.switchAccount(any()) }
            vm.switchAccount(second.key)
            vm.switchAccount(second.key)
            assertEquals(second.key, vm.state.value.switchingKey)
            coVerify(exactly = 1) { auth.switchAccount(second.key) }
            response.complete(ApiResult.Failure(ApiError.NetworkError))
            assertSame(started, accounts.session.epoch)
            assertEquals(first, accounts.session.state.value.activeAccount)
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.switchingKey)
            assertNotNull(vm.state.value.inlineErrorRes)

            coEvery { auth.switchAccount(second.key) } returns ApiResult.Success(Unit)
            vm.switchAccount(second.key)
            assertTrue(vm.state.value.loginSucceeded)
            coVerify(exactly = 2) { auth.switchAccount(second.key) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `需重新授权的账号打开PAT但不尝试切换`() = runTest {
        val second = GitHubAccount("2", 2, "U_2", "second", needsReauthentication = true)
        accounts.session.replace(listOf(second), null)
        val auth = mockk<AuthRepository>()
        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)
        try {
            vm.switchAccount(second.key)
            assertEquals(AuthSheet.PAT, vm.state.value.authSheet)
            assertEquals(R.string.accounts_reauthenticate, vm.state.value.inlineErrorRes)
            assertFalse(vm.state.value.isLoading)
            coVerify(exactly = 0) { auth.switchAccount(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `会话改变后迟到授权结果不显示成功或新账号错误`() = runTest {
        val auth = mockk<AuthRepository>()
        val response = CompletableDeferred<ApiResult<AuthenticatedUser>>()
        coEvery { auth.loginWithToken("candidate") } coAnswers { withContext(NonCancellable) { response.await() } }
        val bus = ErrorEventBus()
        val vm = LoginViewModel(auth, bus, accounts.session)
        try {
            vm.onTokenInputChange("candidate")
            vm.login()
            val second = GitHubAccount("2", 2, "U_2", "second")
            accounts.session.replace(listOf(second), second.key)
            val events = mutableListOf<BannerEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                bus.eventsFor(accounts.session.epoch).collect { events += it }
            }
            response.complete(ApiResult.Failure(ApiError.Unauthorized))
            runCurrent()
            assertFalse(vm.state.value.loginSucceeded)
            assertNull(vm.state.value.inlineErrorRes)
            assertTrue(events.isEmpty())
        } finally {
            response.complete(ApiResult.Success(fakeViewerProfile()))
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `浏览器打开失败报告顶部错误但不取消授权码`() = runTest {
        val auth = mockk<AuthRepository>()
        val waiting = DeviceFlowState.Waiting("ABCD-EFGH", "https://github.com/login/device", 600)
        every { auth.authorizeDevice() } returns flow { emit(waiting); awaitCancellation() }
        val bus = ErrorEventBus()
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.eventsFor(accounts.session.epoch).collect { events += it }
        }
        val vm = LoginViewModel(auth, bus, accounts.session)
        try {
            vm.openDeviceFlow()
            vm.browserUnavailable()
            runCurrent()
            assertEquals(listOf(BannerEvent.Error(ApiError.UserVisible(R.string.auth_browser_unavailable))), events)
            assertEquals(waiting, vm.state.value.deviceFlow)
            assertTrue(vm.state.value.isLoading)
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
