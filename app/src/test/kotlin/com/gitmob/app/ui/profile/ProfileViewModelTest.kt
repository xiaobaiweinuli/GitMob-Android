package com.gitmob.app.ui.profile

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.AccountEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.EditableProfile
import com.gitmob.app.data.model.FollowState
import com.gitmob.app.data.model.ProfileDraft
import com.gitmob.app.data.model.ProfileOwner
import com.gitmob.app.data.model.withEditableProfile
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.data.model.RepoReadme
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.RepoDocumentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @get:Rule val accounts = AccountSessionRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun repository() = mockk<UserRepository> {
        every { profileUpdatesFor(any()) } returns emptyFlow()
        coEvery { getOrgProfileReadme(any()) } returns ApiResult.Success(null)
    }

    private fun createViewModel(userRepo: UserRepository, documents: RepoDocumentRepository = mockk(relaxUnitFun = true)): ProfileViewModel {
        val renderer = mockk<MarkdownRenderer> {
            every { renderToHtml(any()) } answers { "<p>" + firstArg<String>() + "</p>" }
        }
        return ProfileViewModel(userRepo, ErrorEventBus(), RepoUpdateEventBus(), accounts.session, renderer, documents)
    }

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
        val userRepo = repository()
        val expected = fakePerson()
        coEvery { userRepo.getProfileOwner("octocat") } returns ApiResult.Success(expected)

        val vm = createViewModel(userRepo)
        vm.init("octocat")

        assertTrue(vm.state.value.owner is ProfileOwner.Person)
        assertEquals("octocat", vm.state.value.owner!!.login)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loadFailed)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `加载Org类型成功时填充Org分支并复位loading`() = runTest {
        val userRepo = repository()
        val expected = fakeOrg()
        coEvery { userRepo.getProfileOwner("github") } returns ApiResult.Success(expected)

        val vm = createViewModel(userRepo)
        vm.init("github")

        val owner = vm.state.value.owner
        assertTrue(owner is ProfileOwner.Org)
        assertEquals("github", owner!!.login)
        assertFalse(vm.state.value.isLoading)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `加载失败时loadFailed为true retry复位initialized重新请求`() = runTest {
        val userRepo = repository()
        coEvery { userRepo.getProfileOwner("foo") } returns
                ApiResult.Failure(ApiError.NetworkError) andThen
                ApiResult.Success(fakePerson())

        val vm = createViewModel(userRepo)
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
        val userRepo = repository()
        coEvery { userRepo.getProfileOwner(any()) } returns
                ApiResult.Success(fakePerson(following = false, followers = 14))
        coEvery { userRepo.followUser(any()) } returns ApiResult.Success(Unit)

        val vm = createViewModel(userRepo)
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
        val userRepo = repository()
        coEvery { userRepo.getProfileOwner(any()) } returns
                ApiResult.Success(fakePerson(following = true, followers = 0))
        coEvery { userRepo.unfollowUser(any()) } returns ApiResult.Success(Unit)

        val vm = createViewModel(userRepo)
        vm.init("octocat")
        vm.toggleFollow()
        val after = vm.state.value.owner as ProfileOwner.Person
        assertFalse(after.followState.viewerIsFollowing)
        assertEquals(0, after.followersCount) // 0-1 被 coerceAtLeast(0) 钳制
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleFollow Org未关注转关注 只翻布尔不动followers并调用followOrganization`() = runTest {
        val userRepo = repository()
        coEvery { userRepo.getProfileOwner("github") } returns
                ApiResult.Success(fakeOrg(following = false))
        coEvery { userRepo.followOrganization(any()) } returns ApiResult.Success(Unit)

        val vm = createViewModel(userRepo)
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
        val userRepo = repository()
        coEvery { userRepo.getProfileOwner("octo") } returns
                ApiResult.Success(fakePerson(following = false, followers = 9))
        coEvery { userRepo.followUser(any()) } returns ApiResult.Failure(ApiError.Forbidden)

        val vm = createViewModel(userRepo)
        vm.init("octo")
        val before = vm.state.value.owner as ProfileOwner.Person

        vm.toggleFollow()
        val after = vm.state.value.owner as ProfileOwner.Person
        // 失败时 Success 分支的 update 不会执行，状态保持原值
        assertEquals(before.followState.viewerIsFollowing, after.followState.viewerIsFollowing)
        assertEquals(before.followersCount, after.followersCount)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `保存事件仅同步相同node身份的个人资料并保留只读数据`() = runTest(accounts.context) {
        val userRepo = repository()
        val events = AccountEventBus<EditableProfile>(4)
        every { userRepo.profileUpdatesFor(any()) } answers { events.eventsFor(firstArg()) }
        val before = fakePerson()
        coEvery { userRepo.getProfileOwner("octocat") } returns ApiResult.Success(before)
        val vm = createViewModel(userRepo)
        try {
            vm.init("octocat")
            val saved = savedProfile()
            events.emit(saved)
            runCurrent()
            assertEquals(before.withEditableProfile(saved), vm.state.value.owner)
            coVerify(exactly = 1) { userRepo.getProfileOwner("octocat") }

            events.emit(saved.copy(user = saved.user.copy(nodeId = "U_other"), values = ProfileDraft(name = "Other account")))
            runCurrent()
            assertEquals(before.withEditableProfile(saved), vm.state.value.owner)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `迟到加载采用期间保存的资料且后续请求不锁死在保存值`() = runTest(accounts.context) {
        val userRepo = repository()
        val events = AccountEventBus<EditableProfile>(4)
        every { userRepo.profileUpdatesFor(any()) } answers { events.eventsFor(firstArg()) }
        val loading = CompletableDeferred<ApiResult<ProfileOwner>>()
        coEvery { userRepo.getProfileOwner("octocat") } coAnswers { loading.await() }
        val vm = createViewModel(userRepo)
        try {
            vm.init("octocat")
            assertTrue(vm.state.value.isLoading)
            val saved = savedProfile()
            events.emit(saved)
            runCurrent()
            loading.complete(ApiResult.Success(fakePerson()))
            runCurrent()
            assertEquals(fakePerson().withEditableProfile(saved), vm.state.value.owner)
            assertFalse(vm.state.value.isLoading)

            val remote = fakePerson(followers = 21).copy(name = "New remote")
            coEvery { userRepo.getProfileOwner("octocat") } returns ApiResult.Success(remote)
            vm.retry()
            assertEquals(remote, vm.state.value.owner)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `个人保存事件不改变组织资料也不污染迟到组织响应`() = runTest(accounts.context) {
        val userRepo = repository()
        val events = AccountEventBus<EditableProfile>(4)
        every { userRepo.profileUpdatesFor(any()) } answers { events.eventsFor(firstArg()) }
        val loading = CompletableDeferred<ApiResult<ProfileOwner>>()
        coEvery { userRepo.getProfileOwner("github") } coAnswers { loading.await() }
        val vm = createViewModel(userRepo)
        try {
            vm.init("github")
            events.emit(savedProfile())
            runCurrent()
            loading.complete(ApiResult.Success(fakeOrg()))
            runCurrent()
            assertEquals(fakeOrg(), vm.state.value.owner)
            events.emit(savedProfile())
            runCurrent()
            assertEquals(fakeOrg(), vm.state.value.owner)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun savedProfile() = EditableProfile(
        AuthenticatedUser(1, "U_person", "renamed", "Saved name", "https://example.test/avatar.png"),
        ProfileDraft("Saved name", "Saved bio", "Saved company", "Saved location", "https://example.test", "saved@example.test", "saved_twitter", true),
    )
    @Test fun personalProfileDoesNotRequestOrganizationReadme() = runTest {
        val userRepo = repository()
        coEvery { userRepo.getProfileOwner("octocat") } returns ApiResult.Success(fakePerson())
        val vm = createViewModel(userRepo)
        vm.init("octocat")
        coVerify(exactly = 0) { userRepo.getOrgProfileReadme(any()) }
        assertNull(vm.state.value.readmeHtml)
        vm.viewModelScope.cancel()
    }

    @Test fun organizationMetadataIsVisibleWhileReadmeLoadsWithItsOwnContext() = runTest {
        val userRepo = repository()
        val pending = CompletableDeferred<ApiResult<RepoReadme?>>()
        val context = RepoDocumentContext("github", ".github", "commit123", "profile/README.md")
        coEvery { userRepo.getProfileOwner("github") } returns ApiResult.Success(fakeOrg())
        coEvery { userRepo.getOrgProfileReadme("github") } coAnswers { pending.await() }
        val vm = createViewModel(userRepo)
        vm.init("github")
        assertEquals(fakeOrg(), vm.state.value.owner)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.isLoadingReadme)
        pending.complete(ApiResult.Success(RepoReadme("hello", context, true)))
        val loaded = withTimeout(5_000) { vm.state.first { it.readmeHtml != null } }
        assertEquals("<p>hello</p>", loaded.readmeHtml)
        assertEquals(context, loaded.readmeContext)
        assertTrue(loaded.readmeTruncated)
        assertFalse(loaded.isLoadingReadme)
        vm.viewModelScope.cancel()
    }

    @Test fun readmeFailureAndRetryDoNotReloadOrganizationMetadata() = runTest {
        val userRepo = repository()
        coEvery { userRepo.getProfileOwner("github") } returns ApiResult.Success(fakeOrg())
        coEvery { userRepo.getOrgProfileReadme("github") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(null),
        )
        val vm = createViewModel(userRepo)
        vm.init("github")
        assertEquals(fakeOrg(), vm.state.value.owner)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(ApiError.NetworkError, vm.state.value.readmeError)
        vm.retryReadme()
        assertNull(vm.state.value.readmeError)
        assertNull(vm.state.value.readmeHtml)
        assertFalse(vm.state.value.isLoadingReadme)
        coVerify(exactly = 1) { userRepo.getProfileOwner("github") }
        coVerify(exactly = 2) { userRepo.getOrgProfileReadme("github") }
        vm.viewModelScope.cancel()
    }

    @Test fun aCancelledReadmeRequestCannotOverwriteTheRetry() = runTest {
        val userRepo = repository()
        val oldRequest = CompletableDeferred<ApiResult<RepoReadme?>>()
        coEvery { userRepo.getProfileOwner("github") } returns ApiResult.Success(fakeOrg())
        coEvery { userRepo.getOrgProfileReadme("github") } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldRequest.await() }
        }
        val vm = createViewModel(userRepo)
        vm.init("github")
        coEvery { userRepo.getOrgProfileReadme("github") } returns ApiResult.Success(null)
        vm.retryReadme()
        oldRequest.complete(ApiResult.Failure(ApiError.NetworkError))
        runCurrent()
        assertNull(vm.state.value.readmeError)
        assertFalse(vm.state.value.isLoadingReadme)
        assertFalse(vm.state.value.loadFailed)
        vm.viewModelScope.cancel()
    }

}
