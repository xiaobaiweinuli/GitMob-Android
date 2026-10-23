package com.gitmob.app.ui.repodetail

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.event.RepoTransferPendingStore
import com.gitmob.app.core.event.PendingRepositoryTransfer
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepoReadme
import com.gitmob.app.data.model.CreatedRepository
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerPage
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryTransferTarget
import com.gitmob.app.data.model.UserListSummary
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.StarRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
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
 *   - MainDispatcherRule
 *   - 不引入 Hilt，直接 new ViewModel(...)，注入 Mock 依赖
 *
 * 覆盖：
 *   - init + load 成功：detail 填充 + README render 后成 HTML，currentRef 用默认分支
 *   - README 缺失：readmeHtml 为 null，不算整页失败
 *   - detail 加载失败时 loadFailed 为 true，retry 成功
 *   - toggleStar 未星标转已星标：stargazerCount 加 1 且广播 StarChanged 事件
 *   - toggleStar 失败：不改任何状态
 *   - 跨 VM 事件：BranchSwitched 触发 currentRef 更新并重新加载 README
 *   - 并发：refresh + toggleStar 顺序执行不崩溃
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeDetail(
        viewerHasStarred: Boolean = false,
        stargazerCount: Int = 42,
        branch: String = "main",
        description: String? = null,
        topics: List<String> = emptyList(),
        capabilities: RepoCapabilities = RepoCapabilities.NONE,
        ownerIsViewer: Boolean = false,
        forkingAllowed: Boolean = true,
    ) = RepoDetail(
        id = "R_123", name = "greetings", ownerLogin = "octo", ownerAvatarUrl = null,
        description = description, homepageUrl = null,
        isPrivate = false, isArchived = false, isTemplate = false, isFork = false,
        forkedFromOwner = null, forkedFromName = null,
        stargazerCount = stargazerCount, viewerHasStarred = viewerHasStarred,
        forkCount = 1, openIssueCount = 2, openPrCount = 3, watcherCount = 4,
        viewerSubscription = "UNSUBSCRIBED",
        licenseName = null, licenseSpdxId = null,
        branchCount = 1, defaultBranchName = branch,
        releaseCount = 0, latestReleaseName = null, latestReleaseTag = null,
        languageName = "Java", languageColor = "#b07219",
        topics = topics,
        capabilities = capabilities,
        ownerIsViewer = ownerIsViewer,
        forkingAllowed = forkingAllowed,
    )

    private val fakeMd = "# Hello\nworld"
    private val fakeHtml = "<h1>Hello</h1>\n<p>world</p>"

    private fun makeFakeRenderer(): MarkdownRenderer {
        val md = mockk<MarkdownRenderer>()
        every { md.renderToHtml(any()) } answers { firstArg<String>().let { "# $it" } } // 随便返回个什么
        return md
    }

    @Test
    fun `init加载成功时填充detail和render后的readme currentRef使用默认分支`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("octo", "greetings") } returns ApiResult.Success(fakeDetail())
        coEvery { detailRepo.getReadme("octo", "greetings", "main") } returns
                ApiResult.Success(RepoReadme(fakeMd, false))
        val md = mockk<MarkdownRenderer>()
        every { md.renderToHtml(fakeMd) } returns fakeHtml

        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), md, RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore())
        vm.init("octo", "greetings")
        val loadedState = withTimeout(5_000) {
            vm.state.first { it.readmeHtml == fakeHtml }
        }
        assertNotNull(loadedState.detail)
        assertEquals("main", loadedState.currentRef)
        assertEquals(fakeHtml, loadedState.readmeHtml)
        assertFalse(loadedState.isLoading)
        assertFalse(loadedState.isLoadingReadme)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `README缺失时readmeHtml为null detail依然加载成功不算整页失败`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("a", "b") } returns ApiResult.Success(fakeDetail())
        coEvery { detailRepo.getReadme("a", "b", "main") } returns
                ApiResult.Failure(ApiError.Unknown("404"))

        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore())
        vm.init("a", "b")
        assertNotNull(vm.state.value.detail)
        assertNull(vm.state.value.readmeHtml)
        assertFalse(vm.state.value.loadFailed)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `detail加载失败时loadFailed为true retry能再次请求`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("x", "y") } returns
                ApiResult.Failure(ApiError.NetworkError) andThen
                ApiResult.Success(fakeDetail())
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns
                ApiResult.Failure(ApiError.Unknown("no"))

        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore())
        vm.init("x", "y")
        assertTrue(vm.state.value.loadFailed)
        assertNull(vm.state.value.detail)

        vm.retry()
        assertFalse(vm.state.value.loadFailed)
        assertNotNull(vm.state.value.detail)
        coVerify(exactly = 2) { detailRepo.getRepoDetail("x", "y") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleStar未星标转已星标 stargazerCount加1并广播StarChanged事件`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("o", "n") } returns ApiResult.Success(
            fakeDetail(viewerHasStarred = false, stargazerCount = 9),
        )
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns
                ApiResult.Failure(ApiError.Unknown("no"))
        val starRepo = mockk<StarRepository>()
        coEvery { starRepo.starRepo(any()) } returns ApiResult.Success(Unit)
        coEvery { starRepo.getLists() } returns ApiResult.Success(emptyList())
        coEvery { starRepo.getListsContaining(any()) } returns ApiResult.Success(emptySet())
        val bus = RepoUpdateEventBus()

        val vm = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), bus, ErrorEventBus(), mockk(), RepoTransferPendingStore())
        vm.init("o", "n")
        assertFalse(vm.state.value.detail!!.viewerHasStarred)
        assertEquals(9, vm.state.value.detail!!.stargazerCount)

        bus.events.test {
            vm.toggleStar()
            assertTrue(vm.state.value.detail!!.viewerHasStarred)
            assertEquals(10, vm.state.value.detail!!.stargazerCount)
            val evt = awaitItem() as RepoUpdateEvent.StarChanged
            assertEquals(true, evt.isStarred)
            assertEquals(10, evt.stargazerCount)
            cancelAndIgnoreRemainingEvents()
        }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleStar失败时不改detail任何字段`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("o", "n") } returns ApiResult.Success(
            fakeDetail(viewerHasStarred = false, stargazerCount = 7),
        )
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns
                ApiResult.Failure(ApiError.Unknown("no"))
        val starRepo = mockk<StarRepository>()
        coEvery { starRepo.starRepo(any()) } returns ApiResult.Failure(ApiError.Forbidden)

        val vm = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore())
        vm.init("o", "n")
        vm.toggleStar()
        // 失败时 Success 分支的 update 不执行，保持原值
        assertFalse(vm.state.value.detail!!.viewerHasStarred)
        assertEquals(7, vm.state.value.detail!!.stargazerCount)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `跨VM事件_BranchSwitched会更新currentRef并重载README`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("a", "b") } returns ApiResult.Success(fakeDetail(branch = "main"))
        coEvery { detailRepo.getReadme("a", "b", "main") } returns
                ApiResult.Success(RepoReadme("# main", false))
        coEvery { detailRepo.getReadme("a", "b", "dev") } returns
                ApiResult.Success(RepoReadme("# dev", false))

        val md = mockk<MarkdownRenderer>()
        every { md.renderToHtml("# main") } returns "<h1>main</h1>"
        every { md.renderToHtml("# dev") } returns "<h1>dev</h1>"

        val bus = RepoUpdateEventBus()
        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), md, bus, ErrorEventBus(), mockk(), RepoTransferPendingStore())
        vm.init("a", "b")
        withTimeout(5_000) {
            vm.state.first { it.readmeHtml == "<h1>main</h1>" }
        }

        bus.emit(RepoUpdateEvent.BranchSwitched(owner = "a", name = "b", ref = "dev"))
        val devState = withTimeout(5_000) {
            vm.state.first { it.currentRef == "dev" && it.readmeHtml == "<h1>dev</h1>" }
        }
        assertEquals("dev", devState.currentRef)
        assertEquals("<h1>dev</h1>", devState.readmeHtml)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `并发场景_refresh和toggleStar同时触发不崩溃`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("c", "d") } returns ApiResult.Success(fakeDetail())
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns
                ApiResult.Failure(ApiError.Unknown("no"))
        val starRepo = mockk<StarRepository>()
        coEvery { starRepo.starRepo(any()) } returns ApiResult.Success(Unit)
        coEvery { starRepo.unstarRepo(any()) } returns ApiResult.Success(Unit)
        coEvery { starRepo.getLists() } returns ApiResult.Success(emptyList())
        coEvery { starRepo.getListsContaining(any()) } returns ApiResult.Success(emptySet())

        val vm = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore())
        vm.init("c", "d")
        vm.toggleStar()
        vm.retry()
        vm.toggleStar()
        assertFalse(vm.state.value.isLoading)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `continue checks target and opens confirmation before submitting a transfer`() = runTest {
        val repository = mockk<RepoDetailRepository>(relaxed = true)
        val vm = transferViewModel(repository)
        coEvery { repository.getTransferTarget("target", "greetings") } returns ApiResult.Success(transferTarget())
        coEvery { repository.transferRepository("octo", "greetings", "target", "greetings") } returns ApiResult.Success(Unit)

        vm.checkTransferTarget()
        advanceUntilIdle()

        assertTrue(vm.state.value.showTransferConfirmation)
        coVerify(exactly = 0) { repository.transferRepository(any(), any(), any(), any()) }
        vm.confirmManagement()
        advanceUntilIdle()
        assertTrue(vm.state.value.transferPending)
        assertEquals("octo", vm.state.value.detail?.ownerLogin)
        coVerify(exactly = 1) { repository.transferRepository("octo", "greetings", "target", "greetings") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `transfer conflict or organization restriction stays on selection step`() = runTest {
        val repository = mockk<RepoDetailRepository>(relaxed = true)
        val vm = transferViewModel(repository)
        val target = transferTarget()
        listOf(target.copy(hasNameConflict = true), target.copy(account = target.account.copy(canCreateRepository = false))).forEach { blocked ->
            coEvery { repository.getTransferTarget("target", "greetings") } returns ApiResult.Success(blocked)
            vm.checkTransferTarget()
            advanceUntilIdle()
            assertEquals(blocked, vm.state.value.checkedTransferTarget)
            assertFalse(vm.state.value.showTransferConfirmation)
            vm.confirmManagement()
        }
        coVerify(exactly = 0) { repository.transferRepository(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `target and name edits invalidate transfer checks including in flight results`() = runTest {
        val repository = mockk<RepoDetailRepository>(relaxed = true)
        val vm = transferViewModel(repository)
        val pending = CompletableDeferred<ApiResult<RepositoryTransferTarget>>()
        coEvery { repository.getTransferTarget("target", "greetings") } coAnswers { pending.await() }
        vm.checkTransferTarget()
        vm.checkTransferTarget()
        assertTrue(vm.state.value.isCheckingTarget)

        vm.editName("renamed")
        pending.complete(ApiResult.Success(transferTarget()))
        advanceUntilIdle()
        assertNull(vm.state.value.checkedTransferTarget)
        assertFalse(vm.state.value.showTransferConfirmation)
        coVerify(exactly = 1) { repository.getTransferTarget("target", "greetings") }

        coEvery { repository.getTransferTarget("target", "renamed") } returns ApiResult.Success(transferTarget())
        vm.checkTransferTarget()
        advanceUntilIdle()
        assertTrue(vm.state.value.showTransferConfirmation)
        vm.editTransferLogin("other")
        assertNull(vm.state.value.checkedTransferTarget)
        assertFalse(vm.state.value.showTransferConfirmation)
        vm.confirmManagement()
        coVerify(exactly = 0) { repository.transferRepository(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    private fun transferTarget() = RepositoryTransferTarget(
        RepositoryCreateOwner("O1", "target", "Target", null, RepositoryCreateOwnerType.ORGANIZATION, true),
        hasNameConflict = false,
    )

    private fun transferViewModel(repository: RepoDetailRepository): RepoDetailViewModel {
        coEvery { repository.getRepoDetail("octo", "greetings") } returns ApiResult.Success(
            fakeDetail(capabilities = RepoPermission.ADMIN.toCapabilities()),
        )
        coEvery { repository.getReadme(any(), any(), any()) } returns ApiResult.Success(RepoReadme(null, false))
        val owners = mockk<RepoRepository>(relaxed = true)
        coEvery { owners.getRepositoryCreateOwners(null) } returns ApiResult.Success(
            RepositoryCreateOwnerPage(
                RepositoryCreateOwner("U1", "octo", null, null, RepositoryCreateOwnerType.USER, true),
                emptyList(), false, null,
            ),
        )
        return RepoDetailViewModel(
            repository, owners, mockk(relaxed = true), makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore(),
        ).apply {
            init("octo", "greetings")
            openManagement(RepoManagementAction.TRANSFER)
            selectTransferTarget(null)
            editTransferLogin("target")
        }
    }

    @Test
    fun `pending transfer survives detail view model recreation and pauses writes`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("octo", "greetings") } returns ApiResult.Success(fakeDetail())
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns ApiResult.Failure(ApiError.Unknown("no"))
        val starRepo = mockk<StarRepository>()
        val store = RepoTransferPendingStore().apply {
            mark(PendingRepositoryTransfer("R_123", "octo", "greetings", "new-owner", "greetings"))
        }

        val first = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), store)
        first.init("octo", "greetings")
        assertTrue(first.state.value.transferPending)
        first.viewModelScope.cancel()

        val reopened = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), store)
        reopened.init("octo", "greetings")
        assertTrue(reopened.state.value.transferPending)
        reopened.toggleStar()
        coVerify(exactly = 0) { starRepo.starRepo(any()) }
        reopened.viewModelScope.cancel()
    }

    @Test
    fun `partial topics update reloads server state after saving information`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>(relaxed = true)
        val initial = fakeDetail(
            description = "old",
            topics = listOf("old-topic"),
            capabilities = RepoPermission.ADMIN.toCapabilities(),
        )
        val reloaded = initial.copy(description = "new", topics = listOf("android"))
        coEvery { detailRepo.getRepoDetail("octo", "greetings") } returnsMany listOf(
            ApiResult.Success(initial),
            ApiResult.Success(reloaded),
        )
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns ApiResult.Failure(ApiError.Unknown("no"))
        coEvery { detailRepo.getAllTopics("octo", "greetings") } returns ApiResult.Success(initial.topics)
        coEvery { detailRepo.updateSettings("R_123", description = "new", homepageUrl = "") } returns
            ApiResult.Success(CreatedRepository("octo", "greetings"))
        coEvery { detailRepo.updateTopics("R_123", listOf("android", "bad-topic")) } returns
            ApiResult.Success(listOf("bad-topic"))
        val viewModel = RepoDetailViewModel(
            detailRepo,
            mockk(relaxed = true),
            mockk(relaxed = true),
            makeFakeRenderer(),
            RepoUpdateEventBus(),
            ErrorEventBus(),
            mockk(),
            RepoTransferPendingStore(),
        )
        viewModel.init("octo", "greetings")
        advanceUntilIdle()

        viewModel.openManagement(RepoManagementAction.INFORMATION)
        advanceUntilIdle()
        viewModel.editDescription("new")
        viewModel.editTopics("android bad-topic")
        viewModel.confirmManagement()
        advanceUntilIdle()

        assertEquals("new", viewModel.state.value.detail?.description)
        assertEquals(listOf("android"), viewModel.state.value.detail?.topics)
        assertNull(viewModel.state.value.managementAction)
        coVerify(exactly = 2) { detailRepo.getRepoDetail("octo", "greetings") }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `star list membership loads with detail and is reused by the selector`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("octo", "greetings") } returns ApiResult.Success(
            fakeDetail(viewerHasStarred = true),
        )
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns ApiResult.Failure(ApiError.Unknown("no"))
        val starRepo = mockk<StarRepository>()
        val list = UserListSummary("L1", "Android", "android", null, false, 1)
        coEvery { starRepo.getLists() } returns ApiResult.Success(listOf(list))
        coEvery { starRepo.getListsContaining("R_123") } returns ApiResult.Success(setOf("L1"))
        val viewModel = RepoDetailViewModel(
            detailRepo, mockk(), starRepo, makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore(),
        )
        viewModel.init("octo", "greetings")
        advanceUntilIdle()

        coVerify(exactly = 1) { starRepo.getLists() }
        coVerify(exactly = 1) { starRepo.getListsContaining("R_123") }
        assertEquals(setOf("L1"), viewModel.state.value.starListMembership)
        assertTrue(viewModel.state.value.starListsLoaded)
        assertFalse(viewModel.state.value.showStarListSelector)
        viewModel.openStarListSelector()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.showStarListSelector)
        assertEquals(setOf("L1"), viewModel.state.value.starListSelection)
        viewModel.createFork()
        viewModel.dismissStarListSelector()
        viewModel.openStarListSelector()
        coVerify(exactly = 1) { starRepo.getLists() }
        coVerify(exactly = 1) { starRepo.getListsContaining("R_123") }
        coVerify(exactly = 0) { detailRepo.createFork(any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `unstarred detail does not read lists or open the selector`() = runTest {
        val stars = mockk<StarRepository>()
        val vm = listViewModel(stars, detail = fakeDetail())
        vm.openStarListSelector()

        assertFalse(vm.state.value.showStarListSelector)
        assertFalse(vm.state.value.starListsLoaded)
        coVerify(exactly = 0) { stars.getLists() }
        coVerify(exactly = 0) { stars.getListsContaining(any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `successful empty lists and membership are reused and can be saved`() = runTest {
        val stars = listRepository()
        coEvery { stars.getLists() } returns ApiResult.Success(emptyList())
        coEvery { stars.getListsContaining("R_123") } returns ApiResult.Success(emptySet())
        coEvery { stars.updateListsForItem("R_123", emptySet()) } returns ApiResult.Success(Unit)
        val vm = listViewModel(stars)

        assertTrue(vm.state.value.starListsLoaded)
        assertTrue(vm.state.value.starListMembership.isEmpty())
        vm.openStarListSelector()
        vm.saveStarLists()

        assertFalse(vm.state.value.showStarListSelector)
        coVerify(exactly = 1) { stars.getLists() }
        coVerify(exactly = 1) { stars.getListsContaining("R_123") }
        coVerify(exactly = 1) { stars.updateListsForItem("R_123", emptySet()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `selector reuses an in flight read and closing it still completes the summary`() = runTest {
        val stars = listRepository()
        val pending = CompletableDeferred<ApiResult<List<UserListSummary>>>()
        coEvery { stars.getLists() } coAnswers { pending.await() }
        val vm = listViewModel(stars)

        assertTrue(vm.state.value.isLoadingStarLists)
        assertTrue(vm.state.value.canStartAction)
        vm.openStarListSelector()
        vm.openStarListSelector()
        vm.toggleStarList("L2")
        vm.openCreateListDialog()
        vm.saveStarLists()
        assertTrue(vm.state.value.showStarListSelector)
        assertFalse(vm.state.value.showCreateListDialog)
        assertTrue(vm.state.value.starListSelection.isEmpty())
        vm.dismissStarListSelector()
        assertTrue(vm.state.value.isLoadingStarLists)

        pending.complete(ApiResult.Success(listFixtures))
        advanceUntilIdle()

        assertFalse(vm.state.value.showStarListSelector)
        assertFalse(vm.state.value.isLoadingStarLists)
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        vm.openStarListSelector()
        assertEquals(setOf("L1"), vm.state.value.starListSelection)
        coVerify(exactly = 1) { stars.getLists() }
        coVerify(exactly = 1) { stars.getListsContaining("R_123") }
        coVerify(exactly = 0) { stars.updateListsForItem(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `either read failure retains the saved summary and reopening retries before replacement`() = runTest {
        for (failMetadata in listOf(true, false)) {
            val stars = listRepository()
            val errors = ErrorEventBus()
            val vm = listViewModel(stars, errors = errors)
            if (failMetadata) {
                coEvery { stars.getLists() } returns ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(listFixtures)
            } else {
                coEvery { stars.getListsContaining("R_123") } returns ApiResult.Failure(ApiError.Forbidden) andThen ApiResult.Success(setOf("L1"))
            }
            errors.events.test {
                vm.retry()
                assertEquals(BannerEvent.Error(if (failMetadata) ApiError.NetworkError else ApiError.Forbidden), awaitItem())
            }

            assertFalse(vm.state.value.starListsLoaded)
            assertFalse(vm.state.value.isLoadingStarLists)
            assertFalse(vm.state.value.loadFailed)
            assertEquals(setOf("L1"), vm.state.value.starListMembership)
            vm.saveStarLists()
            coVerify(exactly = 0) { stars.updateListsForItem(any(), any()) }
            vm.openStarListSelector()
            assertTrue(vm.state.value.starListsLoaded)
            assertEquals(setOf("L1"), vm.state.value.starListSelection)
            coVerify(exactly = 3) { stars.getLists() }
            coVerify(exactly = if (failMetadata) 2 else 3) { stars.getListsContaining("R_123") }
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `membership failure while selector is open closes it without an incomplete write`() = runTest {
        val stars = listRepository()
        val pending = CompletableDeferred<ApiResult<Set<String>>>()
        coEvery { stars.getListsContaining("R_123") } coAnswers { pending.await() }
        val vm = listViewModel(stars)
        vm.openStarListSelector()
        vm.saveStarLists()
        pending.complete(ApiResult.Failure(ApiError.NetworkError))
        advanceUntilIdle()

        assertFalse(vm.state.value.showStarListSelector)
        assertFalse(vm.state.value.starListsLoaded)
        assertFalse(vm.state.value.isLoadingStarLists)
        coVerify(exactly = 0) { stars.updateListsForItem(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `cancelled selection never changes the saved summary and reopening discards the draft`() = runTest {
        val stars = listRepository()
        val vm = listViewModel(stars)
        vm.openStarListSelector()
        vm.toggleStarList("L2")

        assertEquals(setOf("L1", "L2"), vm.state.value.starListSelection)
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        vm.dismissStarListSelector()
        vm.toggleStarList("L2")
        vm.openStarListSelector()

        assertEquals(setOf("L1"), vm.state.value.starListSelection)
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        coVerify(exactly = 0) { stars.updateListsForItem(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `failed list save retains the draft and only successful retry updates membership`() = runTest {
        val stars = listRepository()
        coEvery { stars.updateListsForItem("R_123", setOf("L1", "L2")) } returns
            ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(Unit)
        val vm = listViewModel(stars)
        vm.openStarListSelector()
        vm.toggleStarList("L2")
        vm.saveStarLists()

        assertTrue(vm.state.value.showStarListSelector)
        assertFalse(vm.state.value.isSavingStarLists)
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        assertEquals(setOf("L1", "L2"), vm.state.value.starListSelection)
        vm.saveStarLists()

        assertFalse(vm.state.value.showStarListSelector)
        assertEquals(setOf("L1", "L2"), vm.state.value.starListMembership)
        coVerify(exactly = 2) { stars.updateListsForItem("R_123", setOf("L1", "L2")) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `detail refresh cannot overwrite editing or saving and reload after save reads fresh membership`() = runTest {
        val stars = listRepository()
        val details = mockk<RepoDetailRepository>()
        val pending = CompletableDeferred<ApiResult<Unit>>()
        coEvery { stars.updateListsForItem("R_123", setOf("L1", "L2")) } coAnswers { pending.await() }
        val vm = listViewModel(stars, details)
        vm.openStarListSelector()
        vm.toggleStarList("L2")
        vm.retry()
        vm.saveStarLists()
        vm.retry()
        vm.saveStarLists()
        vm.toggleStarList("L2")
        vm.dismissStarListSelector()

        assertTrue(vm.state.value.isSavingStarLists)
        assertTrue(vm.state.value.showStarListSelector)
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        coVerify(exactly = 1) { details.getRepoDetail("octo", "greetings") }
        coVerify(exactly = 1) { stars.getListsContaining("R_123") }
        pending.complete(ApiResult.Success(Unit))
        advanceUntilIdle()
        assertEquals(setOf("L1", "L2"), vm.state.value.starListMembership)

        coEvery { stars.getListsContaining("R_123") } returns ApiResult.Success(setOf("L2"))
        vm.retry()
        assertEquals(setOf("L2"), vm.state.value.starListMembership)
        coVerify(exactly = 2) { details.getRepoDetail("octo", "greetings") }
        coVerify(exactly = 2) { stars.getListsContaining("R_123") }
        coVerify(exactly = 1) { stars.updateListsForItem("R_123", setOf("L1", "L2")) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `unstar cancels old membership and its late result cannot overwrite a newer save`() = runTest {
        val stars = listRepository()
        val pending = CompletableDeferred<ApiResult<Set<String>>>()
        coEvery { stars.getListsContaining("R_123") } coAnswers { withContext(NonCancellable) { pending.await() } }
        coEvery { stars.unstarRepo("R_123") } returns ApiResult.Success(Unit)
        coEvery { stars.starRepo("R_123") } returns ApiResult.Success(Unit)
        coEvery { stars.updateListsForItem("R_123", setOf("L1", "L2")) } returns ApiResult.Success(Unit)
        val vm = listViewModel(stars)

        vm.toggleStar()
        assertFalse(vm.state.value.detail!!.viewerHasStarred)
        assertFalse(vm.state.value.isLoadingStarLists)
        assertTrue(vm.state.value.starListMembership.isEmpty())
        coEvery { stars.getListsContaining("R_123") } returns ApiResult.Success(setOf("L2"))
        vm.toggleStar()
        vm.openStarListSelector()
        vm.toggleStarList("L1")
        vm.saveStarLists()
        pending.complete(ApiResult.Success(setOf("L1")))
        advanceUntilIdle()

        assertTrue(vm.state.value.detail!!.viewerHasStarred)
        assertTrue(vm.state.value.starListsLoaded)
        assertEquals(setOf("L1", "L2"), vm.state.value.starListMembership)
        coVerify(exactly = 2) { stars.getListsContaining("R_123") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `failed unstar resumes membership loading after cancelling the old read`() = runTest {
        val stars = listRepository()
        val pending = CompletableDeferred<ApiResult<Set<String>>>()
        coEvery { stars.getListsContaining("R_123") } coAnswers { pending.await() }
        coEvery { stars.unstarRepo("R_123") } returns ApiResult.Failure(ApiError.Forbidden)
        val vm = listViewModel(stars)
        coEvery { stars.getListsContaining("R_123") } returns ApiResult.Success(setOf("L1"))

        vm.toggleStar()
        pending.complete(ApiResult.Success(emptySet()))
        advanceUntilIdle()

        assertTrue(vm.state.value.detail!!.viewerHasStarred)
        assertTrue(vm.state.value.starListsLoaded)
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        coVerify(exactly = 2) { stars.getListsContaining("R_123") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `background membership read does not block fork or management submission`() = runTest {
        val stars = listRepository()
        val pending = CompletableDeferred<ApiResult<Set<String>>>()
        coEvery { stars.getListsContaining("R_123") } coAnswers { pending.await() }
        justRun { stars.invalidateAllCaches() }
        val details = mockk<RepoDetailRepository>(relaxed = true)
        val detail = fakeDetail(viewerHasStarred = true, capabilities = RepoPermission.ADMIN.toCapabilities())
        val vm = listViewModel(stars, details, detail)
        coEvery { details.createFork("octo", "greetings") } returns ApiResult.Success(Unit)
        coEvery { details.setArchived("R_123", true) } returns ApiResult.Success(Unit)
        coEvery { details.getRepoDetail("octo", "greetings") } returns ApiResult.Success(detail.copy(isArchived = true))

        vm.createFork()
        vm.openManagement(RepoManagementAction.ARCHIVE)
        vm.confirmManagement()

        assertTrue(vm.state.value.isLoadingStarLists)
        assertTrue(vm.state.value.detail!!.isArchived)
        coVerify(exactly = 1) { details.createFork("octo", "greetings") }
        coVerify(exactly = 1) { details.setArchived("R_123", true) }
        pending.complete(ApiResult.Success(setOf("L1")))
        advanceUntilIdle()
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `new list remains draft membership until the selection is saved`() = runTest {
        val stars = listRepository()
        val created = UserListSummary("L3", "New", "new", null, false, 0)
        coEvery { stars.createList("New", null, false) } returns ApiResult.Success(created)
        val vm = listViewModel(stars)
        vm.openStarListSelector()
        vm.openCreateListDialog()
        vm.createList("New", null, false)

        assertEquals(listFixtures + created, vm.state.value.starLists)
        assertEquals(setOf("L1", "L3"), vm.state.value.starListSelection)
        assertEquals(setOf("L1"), vm.state.value.starListMembership)
        vm.dismissStarListSelector()
        vm.openStarListSelector()
        assertEquals(setOf("L1"), vm.state.value.starListSelection)
        coVerify(exactly = 0) { stars.updateListsForItem(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `accepted fork does not change fork count or navigate state`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("octo", "greetings") } returns ApiResult.Success(fakeDetail())
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns ApiResult.Failure(ApiError.Unknown("no"))
        coEvery { detailRepo.createFork("octo", "greetings") } returns ApiResult.Success(Unit)
        val viewModel = RepoDetailViewModel(
            detailRepo, mockk(), mockk(), makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore(),
        )
        viewModel.init("octo", "greetings")
        advanceUntilIdle()

        viewModel.createFork()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.detail?.forkCount)
        assertFalse(viewModel.state.value.isForking)
        coVerify(exactly = 1) { detailRepo.createFork("octo", "greetings") }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `viewer owned repository never submits a fork request`() = runTest {
        val detailRepo = mockk<RepoDetailRepository>()
        coEvery { detailRepo.getRepoDetail("octo", "greetings") } returns ApiResult.Success(
            fakeDetail(ownerIsViewer = true),
        )
        coEvery { detailRepo.getReadme(any(), any(), any()) } returns ApiResult.Failure(ApiError.Unknown("no"))
        val viewModel = RepoDetailViewModel(
            detailRepo, mockk(), mockk(), makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore(),
        )
        viewModel.init("octo", "greetings")
        advanceUntilIdle()

        viewModel.createFork()

        coVerify(exactly = 0) { detailRepo.createFork(any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    private val listFixtures = listOf(
        UserListSummary("L1", "Android", "android", null, false, 1),
        UserListSummary("L2", "Tools", "tools", null, false, 2),
    )

    private fun listRepository() = mockk<StarRepository>().also { stars ->
        coEvery { stars.getLists() } returns ApiResult.Success(listFixtures)
        coEvery { stars.getListsContaining("R_123") } returns ApiResult.Success(setOf("L1"))
    }

    private fun listViewModel(
        stars: StarRepository,
        details: RepoDetailRepository = mockk(),
        detail: RepoDetail = fakeDetail(viewerHasStarred = true),
        errors: ErrorEventBus = ErrorEventBus(),
    ): RepoDetailViewModel {
        coEvery { details.getRepoDetail("octo", "greetings") } returns ApiResult.Success(detail)
        coEvery { details.getReadme(any(), any(), any()) } returns ApiResult.Success(RepoReadme(null, false))
        return RepoDetailViewModel(
            details, mockk(relaxed = true), stars, makeFakeRenderer(), RepoUpdateEventBus(), errors, mockk(), RepoTransferPendingStore(),
        ).apply { init("octo", "greetings") }
    }
}
