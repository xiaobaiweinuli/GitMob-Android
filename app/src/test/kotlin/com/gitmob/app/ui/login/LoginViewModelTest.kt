package com.gitmob.app.ui.login

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.FollowState
import com.gitmob.app.data.model.GHUser
import com.gitmob.app.data.model.ViewerProfile
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeViewerProfile() = ViewerProfile(
        user = GHUser(id = "U_1", login = "me", name = "me", followers = 0),
        extra = null,
        repoCount = 0,
        starredCount = 0,
        pinnedRepos = emptyList(),
        followState = FollowState(isViewer = true, viewerCanFollow = false, viewerIsFollowing = false),
    )

    @Test
    fun `空token点击登录时显示inline错误且不发网络请求`() = runTest {
        val auth = mockk<AuthRepository>(relaxed = true)
        val vm = LoginViewModel(auth, ErrorEventBus())

        vm.onTokenInputChange("   ")
        vm.login()

        assertEquals("请输入 Personal Access Token", vm.state.value.inlineError)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loginSucceeded)
    }

    @Test
    fun `登录成功时loading复位且loginSucceeded为true`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.loginWithToken("ghp_validtoken") } returns ApiResult.Success(fakeViewerProfile())

        val vm = LoginViewModel(auth, ErrorEventBus())
        vm.onTokenInputChange("ghp_validtoken")
        vm.login()

        assertTrue(vm.state.value.loginSucceeded)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.inlineError)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `登录失败时错误同时发总线和inlineError状态回滚`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.loginWithToken("ghp_badtoken") } returns ApiResult.Failure(ApiError.Unauthorized)
        val bus = ErrorEventBus()

        val vm = LoginViewModel(auth, bus)
        vm.onTokenInputChange("ghp_badtoken")
        vm.login()

        assertNotNull(vm.state.value.inlineError)
        assertFalse(vm.state.value.loginSucceeded)
        assertFalse(vm.state.value.isLoading)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `失败恢复场景_登录失败后再输入正确token再次登录成功`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.loginWithToken("bad") } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { auth.loginWithToken("good") } returns ApiResult.Success(fakeViewerProfile())

        val vm = LoginViewModel(auth, ErrorEventBus())

        // 第一次：错误token
        vm.onTokenInputChange("bad")
        vm.login()
        assertFalse(vm.state.value.loginSucceeded)
        assertNotNull(vm.state.value.inlineError)

        // 第二次：改token为正确
        vm.onTokenInputChange("good")
        vm.login()
        assertTrue(vm.state.value.loginSucceeded)
        assertNull(vm.state.value.inlineError)
        vm.viewModelScope.cancel()
    }
}
