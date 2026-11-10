package com.gitmob.app.ui.login

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.AccountAuthMethod
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class LoginReauthenticationTest {
    private val accounts = AccountSessionRule(null)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)

    @Test
    fun `已标记失效账号按原登录方式打开面板`() = runTest {
        for (method in AccountAuthMethod.entries) checkReauthorization(method, alreadyInvalid = true)
    }

    @Test
    fun `切换时才发现失效也按原登录方式打开面板并保留当前账号`() = runTest {
        for (method in AccountAuthMethod.entries) checkReauthorization(method, alreadyInvalid = false)
    }

    private fun checkReauthorization(method: AccountAuthMethod, alreadyInvalid: Boolean) {
        val current = GitHubAccount("1", 1, "U_1", "current")
        val selected = GitHubAccount("2", 2, "U_2", "selected", authMethod = method, needsReauthentication = alreadyInvalid)
        accounts.session.replace(listOf(current, selected), current.key)
        val epoch = accounts.session.epoch
        val auth = mockk<AuthRepository>()
        coEvery { auth.switchAccount(selected.key) } returns ApiResult.Failure(ApiError.Unauthorized)
        var deviceCancelled = false
        every { auth.authorizeDevice() } returns flow {
            try { emit(DeviceFlowState.Requesting); awaitCancellation() }
            finally { deviceCancelled = true }
        }
        val vm = LoginViewModel(auth, ErrorEventBus(), accounts.session)
        try {
            vm.switchAccount(selected.key)
            val device = method == AccountAuthMethod.DEVICE_FLOW
            assertEquals(if (device) AuthSheet.DEVICE_FLOW else AuthSheet.PAT, vm.state.value.authSheet)
            assertEquals(R.string.accounts_reauthenticate, vm.state.value.operationErrorRes)
            assertEquals(device, vm.state.value.isLoading)
            assertNull(vm.state.value.switchingKey)
            assertSame(epoch, accounts.session.epoch)
            assertEquals(current, accounts.session.state.value.activeAccount)
            coVerify(exactly = if (alreadyInvalid) 0 else 1) { auth.switchAccount(selected.key) }
            verify(exactly = if (device) 1 else 0) { auth.authorizeDevice() }
            vm.cancelAuthorization()
            assertEquals(LoginUiState(), vm.state.value)
            assertEquals(device, deviceCancelled)
        } finally { vm.viewModelScope.cancel() }
    }
}
