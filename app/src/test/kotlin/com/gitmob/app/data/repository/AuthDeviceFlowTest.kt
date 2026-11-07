package com.gitmob.app.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountCredentials
import com.gitmob.app.core.auth.DeviceCodeResponse
import com.gitmob.app.core.auth.GitHubOAuthClient
import com.gitmob.app.core.auth.OAuthTokenResponse
import com.gitmob.app.core.auth.StoredAccounts
import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.network.GitHubIdentityClient
import com.gitmob.app.data.model.AccountAuthMethod
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.model.DeviceFlowStatus
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthDeviceFlowTest {
    @get:Rule val accounts = AccountSessionRule(null)
    private val storage = mockk<TokenStorage>(relaxed = true)
    private val identity = mockk<GitHubIdentityClient>()
    private val oauth = mockk<GitHubOAuthClient>()
    private val user = AuthenticatedUser(42, "U_42", "candidate", null, null)
    private val savedAccount = GitHubAccount("42", 42, "U_42", "candidate", authMethod = AccountAuthMethod.DEVICE_FLOW)

    private fun repository(): AuthRepository {
        coEvery { oauth.startDeviceFlow() } returns code()
        coEvery { identity.verify("device-access") } returns user
        coEvery { storage.snapshot() } returns StoredAccounts(listOf(savedAccount), savedAccount.key)
        return AuthRepository(storage, identity, accounts.session, oauth, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `关闭等待面板取消轮询且不提交凭据`() = runTest(accounts.context) {
        val auth = repository()
        coEvery { oauth.poll(any()) } returns OAuthTokenResponse(error = "authorization_pending")
        val states = mutableListOf<DeviceFlowState>()
        val job = launch { auth.authorizeDevice().collect { states += it } }
        runCurrent()
        assertTrue(states.last() is DeviceFlowState.Waiting)
        advanceTimeBy(4_999)
        runCurrent()
        coVerify(exactly = 0) { oauth.poll(any()) }
        job.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        job.join()
        coVerify(exactly = 0) { oauth.poll(any()) }
        coVerify(exactly = 0) { storage.saveAccount(any(), any(), any(), any()) }
    }

    @Test
    fun `取消发生在身份验证完成前不能提交迟到成功`() = runTest(accounts.context) {
        val auth = repository()
        coEvery { oauth.poll(any()) } returns token()
        val verification = CompletableDeferred<AuthenticatedUser>()
        coEvery { identity.verify(any()) } coAnswers { withContext(NonCancellable) { verification.await() } }
        val epoch = accounts.session.epoch
        val states = mutableListOf<DeviceFlowState>()
        val job = launch { auth.authorizeDevice().collect { states += it } }
        advanceTimeBy(5_000)
        runCurrent()
        coVerify(exactly = 1) { identity.verify("device-access") }

        job.cancel()
        verification.complete(user)
        runCurrent()
        job.join()

        assertSame(epoch, accounts.session.epoch)
        assertTrue(states.none { it is DeviceFlowState.Complete })
        coVerify(exactly = 0) { storage.saveAccount(any(), any(), any(), any()) }
    }

    @Test
    fun `提交已开始时取消仍原子完成持久化和会话发布但不发迟到Complete`() = runTest(accounts.context) {
        val auth = repository()
        coEvery { oauth.poll(any()) } returns token()
        val persist = CompletableDeferred<Unit>()
        coEvery { storage.saveAccount(any(), any(), any(), any()) } coAnswers {
            persist.await()
            emptyPreferences()
        }
        val epoch = accounts.session.epoch
        val states = mutableListOf<DeviceFlowState>()
        val job = launch { auth.authorizeDevice().collect { states += it } }
        advanceTimeBy(5_000)
        runCurrent()
        coVerify(exactly = 1) { storage.saveAccount(user, any(), null, true) }
        assertSame(epoch, accounts.session.epoch)

        job.cancel()
        persist.complete(Unit)
        runCurrent()
        job.join()

        assertFalse(epoch.isCurrent)
        assertEquals(savedAccount, accounts.session.state.value.activeAccount)
        assertTrue(states.none { it is DeviceFlowState.Complete })
        coVerify(exactly = 1) {
            storage.saveAccount(user, match<AccountCredentials> {
                it.accessToken == "device-access" && it.refreshToken == "device-refresh" &&
                    it.authMethod == AccountAuthMethod.DEVICE_FLOW && it.expiresAtMillis != null
            }, null, true)
        }
    }

    @Test
    fun `DeviceFlow提交持久化失败保留旧会话且返回失败可重试`() = runTest(accounts.context) {
        val auth = repository()
        coEvery { oauth.poll(any()) } returns token()
        coEvery { storage.saveAccount(any(), any(), any(), any()) } throws IOException("Storage unavailable")
        val epoch = accounts.session.epoch
        val states = mutableListOf<DeviceFlowState>()
        val job = launch { auth.authorizeDevice().collect { states += it } }
        advanceTimeBy(5_000)
        runCurrent()
        job.join()

        assertSame(epoch, accounts.session.epoch)
        assertEquals(DeviceFlowState.Failed(ApiError.NetworkError), states.last())
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
        coVerify(exactly = 0) { storage.requireReauthentication(any()) }
    }

    @Test
    fun `slow_down至少加五秒且网络失败遵循退避不提前轮询`() = runTest(accounts.context) {
        val auth = repository()
        coEvery { oauth.poll(any()) } returnsMany listOf(
            OAuthTokenResponse(error = "slow_down", interval = 6),
            OAuthTokenResponse(error = "authorization_pending"),
        )
        val states = mutableListOf<DeviceFlowState>()
        val job = launch { auth.authorizeDevice().collect { states += it } }
        try {
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(DeviceFlowStatus.SLOW_DOWN, (states.last() as DeviceFlowState.Waiting).status)
            advanceTimeBy(9_999)
            runCurrent()
            coVerify(exactly = 1) { oauth.poll(any()) }
            advanceTimeBy(1)
            runCurrent()
            coVerify(exactly = 2) { oauth.poll(any()) }

            coEvery { oauth.poll(any()) } throws IOException("Offline")
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(DeviceFlowStatus.NETWORK_RETRY, (states.last() as DeviceFlowState.Waiting).status)
            advanceTimeBy(19_999)
            runCurrent()
            coVerify(exactly = 3) { oauth.poll(any()) }
            advanceTimeBy(1)
            runCurrent()
            coVerify(exactly = 4) { oauth.poll(any()) }
        } finally {
            job.cancel()
            job.join()
        }
    }

    @Test
    fun `授权终态错误正确分类且不保存凭据`() = runTest(accounts.context) {
        val auth = repository()
        for ((error, resource) in listOf(
            "expired_token" to R.string.auth_device_expired,
            "access_denied" to R.string.auth_device_denied,
            "device_flow_disabled" to R.string.auth_device_disabled,
            "incorrect_device_code" to R.string.auth_device_invalid,
        )) {
            coEvery { oauth.poll(any()) } returns OAuthTokenResponse(error = error)
            val states = mutableListOf<DeviceFlowState>()
            val job = launch { auth.authorizeDevice().collect { states += it } }
            advanceTimeBy(5_000)
            runCurrent()
            job.join()
            assertEquals(DeviceFlowState.Failed(ApiError.UserVisible(resource)), states.last())
        }
        coVerify(exactly = 0) { identity.verify(any()) }
        coVerify(exactly = 0) { storage.saveAccount(any(), any(), any(), any()) }
    }

    @Test
    fun `不完整验证码和非GitHub授权URL在轮询前拒绝`() = runTest(accounts.context) {
        val auth = repository()
        for (invalid in listOf(code(uri = "https://example.test/login/device"), code(expires = 0), code(interval = 0))) {
            coEvery { oauth.startDeviceFlow() } returns invalid
            val states = mutableListOf<DeviceFlowState>()
            auth.authorizeDevice().collect { states += it }
            assertTrue(states.last() is DeviceFlowState.Failed)
        }
        coVerify(exactly = 0) { oauth.poll(any()) }
    }

    private fun code(uri: String = "https://github.com/login/device", expires: Long = 900, interval: Long = 5) = DeviceCodeResponse(
        deviceCode = "device-code", userCode = "TEST-CODE", verificationUri = uri, expiresIn = expires, interval = interval,
    )

    private fun token() = OAuthTokenResponse(
        accessToken = "device-access", refreshToken = "device-refresh", expiresIn = 3600,
        refreshExpiresIn = 86_400, tokenType = "bearer",
    )
}
