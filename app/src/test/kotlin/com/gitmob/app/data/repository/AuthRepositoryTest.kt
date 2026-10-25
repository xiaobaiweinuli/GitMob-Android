package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.error.ApiResult
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRepositoryTest {

    @Test
    fun `退出登录清空全部缓存并删除当前账号凭据`() = runTest {
        val tokenStorage = mockk<TokenStorage>(relaxed = true)
        val userRepository = mockk<UserRepository>(relaxed = true)
        val starRepository = mockk<StarRepository>(relaxed = true)
        val repoRepository = mockk<RepoRepository>(relaxed = true)
        val gistRepository = mockk<GistRepository>(relaxed = true)
        val repoDetailRepository = mockk<RepoDetailRepository>(relaxed = true)
        val notificationRepository = mockk<NotificationRepository>(relaxed = true)
        val repository = AuthRepository(
            tokenStorage,
            userRepository,
            starRepository,
            repoRepository,
            gistRepository,
            repoDetailRepository,
            notificationRepository,
        )

        val result = repository.logout()

        assertEquals(ApiResult.Success(Unit), result)
        verify(exactly = 1) { userRepository.invalidateAllCaches() }
        verify(exactly = 1) { starRepository.invalidateAllCaches() }
        verify(exactly = 1) { repoRepository.invalidateAllCaches() }
        verify(exactly = 1) { gistRepository.invalidateAllCaches() }
        verify(exactly = 1) { repoDetailRepository.invalidateAllCaches() }
        verify(exactly = 1) { notificationRepository.invalidateAllCaches() }
        coVerify(exactly = 1) { tokenStorage.clearActiveAccount() }
    }
}
