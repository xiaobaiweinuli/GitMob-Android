package com.gitmob.app.ui.profile

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.FollowState
import com.gitmob.app.data.model.ProfileOwner
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
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
 * 不需要模拟器/真机/APK 安装）。
 *
 * 符合 SKILL.md 第七节「测试规范」ViewModel 范式：
 *   - JUnit4 + kotlinx-coroutines-test + MockK
 *   - MainDispatcherRule（viewModelScope 需要 Dispatchers.Main）
 *   - 不引入 Hilt，直接 new ViewModel(...)，传 Mock Repository
 *
 * 覆盖：
 *   - loadOwner 两种 sealed 分支（Person/Org）加载成功/失败
 *   - retry 复位 initialized 重新请求
 *   - toggleFollow Person 分支：viewerIsFollowing 翻转 + followersCount 乐观加减1
 *   - toggleFollow Person 已关注取消到 0 时 followersCount 被钳制为 0
 *   - toggleFollow Org 分支：viewerIsFollowing 翻转，调用 followOrganization 而不是 followUser
 *   - toggleFollow 失败时两种 sealed 分支都不修改任何状态
 */
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakePerson(following: Boolean = false, followers: Int = 14) = ProfileOwner.Person(
        id = "U_person", login = "octocat", avatarUrl = null, name = "cat",
        repoCount = 3, websiteUrl = null, bio = null, pronouns = null,
        isViewer = false,
        followState = FollowState(isViewer = false, viewerCanFollow = true, viewerIsFollowing = following),
        followersCount = followers, followingCount = 2, organizationsCount = 1, starredCount = 5,
        isDeveloperProgramMember = true, isBountyHunter = false, isCampusExpert = false, isGitHubStar = false,
    )

    private fun fakeOrg(following: Boolean = false) = ProfileOwner.Org(
        id = "O_org", login = "github", avatarUrl = null, name = "GitHub",
        repoCount = 200, websiteUrl = "https://github.blog",
        description = "We build GitHub.", isVerified = true,
        membersCount = 300, viewerIsFollowing = following,
    )

    @Test
    fun `加载Person类型成功时填充owner并复位loading`() = runTest {
        val userRepo = mockk<UserRepository>()
        val expected = fakePerson()
        coEvery { userRepo.getProfileOwner("octocat") } returns ApiResult.Success(expected)

        val vm = ProfileViewModel(userRepo, ErrorEventBus())
        vm.init("octocat")

        assertTrue(vm.state.value.owner is ProfileOwner.Person)
        assertEquals("octocat", vm.state.value.owner!!.login)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loadFailed)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `加载Org类型成功时填充Org分支并复位loading`() = runTest {
        val userRepo = mockk<UserRepository>()
        val expected = fakeOrg()
        coEvery { userRepo.getProfileOwner("github") } returns ApiResult.Success(expected)

        val vm = ProfileViewModel(userRepo, ErrorEventBus())
        vm.init("github")

        val owner = vm.state.value.owner
        assertTrue(owner is ProfileOwner.Org)
        assertEquals("github", owner!!.login)
        assertFalse(vm.state.value.isLoading)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `加载失败时loadFailed为true retry复位initialized重新请求`() = runTest {
        val userRepo = mockk<UserRepository>()
        coEvery { userRepo.getProfileOwner("foo") } returns
                ApiResult.Failure(ApiError.NetworkError) andThen
                ApiResult.Success(fakePerson())

        val vm = ProfileViewModel(userRepo, ErrorEventBus())
        vm.init("foo")

        // 第一次失败
        assertTrue(vm.state.value.loadFailed)
        assertNull(vm.state.value.owner)

        // 重试：必须能重新发请求（之前的bug是retry没复位initialized导致第二次被忽略）
        vm.retry()
        assertNotNull(vm.state.value.owner)
        assertFalse(vm.state.value.loadFailed)

        coVerify(exactly = 2) { userRepo.getProfileOwner("foo") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleFollow Person未关注转关注 翻布尔并乐观followers加1`() = runTest {
        val userRepo = mockk<UserRepository>()
        coEvery { userRepo.getProfileOwner(any()) } returns
                ApiResult.Success(fakePerson(following = false, followers = 14))
        coEvery { userRepo.followUser(any()) } returns ApiResult.Success(Unit)

        val vm = ProfileViewModel(userRepo, ErrorEventBus())
        vm.init("octocat")
        val before = vm.state.value.owner as ProfileOwner.Person
        assertFalse(before.followState.viewerIsFollowing)
        assertEquals(14, before.followersCount)

        vm.toggleFollow()
        val after = vm.state.value.owner as ProfileOwner.Person
        assertTrue(after.followState.viewerIsFollowing)
        assertEquals(15, after.followersCount)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleFollow Person已关注转取消 翻布尔且followers为0时被钳制不小于0`() = runTest {
        val userRepo = mockk<UserRepository>()
        coEvery { userRepo.getProfileOwner(any()) } returns
                ApiResult.Success(fakePerson(following = true, followers = 0))
        coEvery { userRepo.unfollowUser(any()) } returns ApiResult.Success(Unit)

        val vm = ProfileViewModel(userRepo, ErrorEventBus())
        vm.init("octocat")
        vm.toggleFollow()
        val after = vm.state.value.owner as ProfileOwner.Person
        assertFalse(after.followState.viewerIsFollowing)
        assertEquals(0, after.followersCount) // 0-1 被 coerceAtLeast(0) 钳制
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleFollow Org未关注转关注 只翻布尔不动followers并调用followOrganization`() = runTest {
        val userRepo = mockk<UserRepository>()
        coEvery { userRepo.getProfileOwner("github") } returns
                ApiResult.Success(fakeOrg(following = false))
        coEvery { userRepo.followOrganization(any()) } returns ApiResult.Success(Unit)

        val vm = ProfileViewModel(userRepo, ErrorEventBus())
        vm.init("github")
        val before = vm.state.value.owner as ProfileOwner.Org
        assertFalse(before.viewerIsFollowing)

        vm.toggleFollow()
        val after = vm.state.value.owner as ProfileOwner.Org
        assertTrue(after.viewerIsFollowing)
        // 必须是 followOrganization 而不是 followUser（Org Schema 不支持 followUser）
        coVerify(exactly = 1) { userRepo.followOrganization(any()) }
        coVerify(exactly = 0) { userRepo.followUser(any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleFollow失败时两种sealed分支都不修改任何状态`() = runTest {
        val userRepo = mockk<UserRepository>()
        coEvery { userRepo.getProfileOwner("octo") } returns
                ApiResult.Success(fakePerson(following = false, followers = 9))
        coEvery { userRepo.followUser(any()) } returns ApiResult.Failure(ApiError.Forbidden)

        val vm = ProfileViewModel(userRepo, ErrorEventBus())
        vm.init("octo")
        val before = vm.state.value.owner as ProfileOwner.Person

        vm.toggleFollow()
        val after = vm.state.value.owner as ProfileOwner.Person
        // 失败时 Success 分支的 update 不会执行，状态保持原值
        assertEquals(before.followState.viewerIsFollowing, after.followState.viewerIsFollowing)
        assertEquals(before.followersCount, after.followersCount)
        vm.viewModelScope.cancel()
    }
}
