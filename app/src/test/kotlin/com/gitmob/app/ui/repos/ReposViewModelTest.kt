package com.gitmob.app.ui.repos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoSort
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryListOwnerContext
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class ReposViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<RepoRepository>(relaxUnitFun = true).also {
        coEvery { it.getRepos(any(), any(), any(), any()) } returns ApiResult.Success(page())
    }
    private val bus = RepoUpdateEventBus()
    private val errors = ErrorEventBus()
    private val models = mutableListOf<ReposViewModel>()

    @After
    fun tearDown() = models.forEach { it.viewModelScope.cancel() }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        ReposViewModel(repository, bus, errors, handle).also { models += it }

    @Test
    fun `初始化填充主体且同主体重复init不请求`() = runTest {
        coEvery { repository.getRepos("me", null, RepoFilters(), false) } returns ApiResult.Success(page(repo("one"), total = 50))
        val vm = viewModel()
        vm.init("me")
        vm.init("ME")
        assertEquals(1, vm.state.value.repos.size)
        assertEquals("me", vm.state.value.ownerContext?.login)
        assertFalse(vm.state.value.isBusy)
        assertTrue(vm.state.value.hasLoaded)
        coVerify(exactly = 1) { repository.getRepos(any(), any(), any(), any()) }
    }

    @Test
    fun `首次失败发顶部错误且重试仍使用原查询`() = runTest {
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { errors.events.collect { events += it } }
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns
            ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(page(repo("retried")))
        val vm = viewModel()
        vm.init(null)
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.hasLoaded)
        assertEquals(listOf(BannerEvent.Error(ApiError.NetworkError)), events)
        vm.retry()
        assertFalse(vm.state.value.loadFailed)
        assertEquals("retried", vm.state.value.repos.single().name)
    }

    @Test
    fun `分页去重后对全体结果重排并累计成功批次`() = runTest {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns
            ApiResult.Success(page(repo("zulu"), repo("beta"), next = "c1", total = 80))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } returns
            ApiResult.Success(page(repo("alpha"), repo("BETA").copy(stargazerCount = 9), total = 80))
        val vm = viewModel()
        vm.init(null)
        vm.onScrolledToTop(vm.state.value.requestGeneration)
        vm.loadMore()
        assertEquals(listOf("alpha", "BETA", "zulu"), vm.state.value.repos.map { it.name })
        assertEquals(9, vm.state.value.repos[1].stargazerCount)
        assertEquals(2, vm.state.value.completedBatches)
        assertFalse(vm.state.value.scrollToTop)
        assertFalse(vm.state.value.hasNextPage)
        vm.loadMore()
        coVerify(exactly = 2) { repository.getRepos(any(), any(), any(), any()) }
    }

    @Test
    fun `刷新保留当前条件旧数据并防重入成功后整体替换`() = runTest {
        val filters = RepoFilters(query = "find", isFork = false, sort = RepoSort.STARS_DESC)
        val refreshed = CompletableDeferred<ApiResult<RepoList>>()
        coEvery { repository.getRepos("owner", null, filters, false) } returns ApiResult.Success(page(repo("old"), next = "old-cursor"))
        coEvery { repository.getRepos("owner", null, filters, true) } coAnswers { refreshed.await() }
        val vm = viewModel(SavedStateHandle(mapOf("repos.query" to "find", "repos.fork" to false, "repos.sort" to RepoSort.STARS_DESC.name)))
        vm.init("owner")
        vm.onScrolledToTop(vm.state.value.requestGeneration)
        vm.refresh()
        vm.refresh()
        vm.loadMore()
        assertTrue(vm.state.value.isRefreshing)
        assertEquals("old", vm.state.value.repos.single().name)
        coVerify(exactly = 1) { repository.getRepos("owner", null, filters, true) }
        coVerify(exactly = 0) { repository.getRepos("owner", "old-cursor", any(), any()) }
        refreshed.complete(ApiResult.Success(page(repo("fresh"), next = "fresh-cursor")))
        runCurrent()
        assertFalse(vm.state.value.isRefreshing)
        assertEquals("fresh", vm.state.value.repos.single().name)
        assertEquals("fresh-cursor", vm.state.value.endCursor)
        assertEquals(1, vm.state.value.completedBatches)
        assertEquals(filters, vm.state.value.filters)
        assertFalse(vm.state.value.scrollToTop)
    }

    @Test
    fun `刷新失败保留原游标和结果且仍可追加`() = runTest {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("old"), next = "c1"))
        coEvery { repository.getRepos(null, null, RepoFilters(), true) } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } returns ApiResult.Success(page(repo("new")))
        val vm = viewModel()
        vm.init(null)
        vm.refresh()
        assertEquals("old", vm.state.value.repos.single().name)
        assertEquals("c1", vm.state.value.endCursor)
        assertEquals(1, vm.state.value.completedBatches)
        assertFalse(vm.state.value.loadFailed)
        vm.loadMore()
        assertEquals(2, vm.state.value.repos.size)
        assertEquals(2, vm.state.value.completedBatches)
    }

    @Test
    fun `搜索300ms防抖且大小写和首尾空白变化不重复请求`() = runTest {
        val vm = viewModel()
        vm.init(null)
        vm.updateSearch("a")
        assertEquals("a", vm.state.value.filters.query)
        advanceTimeBy(150)
        vm.updateSearch("ab")
        advanceTimeBy(299)
        runCurrent()
        coVerify(exactly = 1) { repository.getRepos(any(), any(), any(), any()) }
        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 1) { repository.getRepos(null, null, RepoFilters(query = "ab"), false) }
        val generation = vm.state.value.requestGeneration
        vm.updateSearch(" AB ")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(" AB ", vm.state.value.filters.query)
        assertEquals(generation, vm.state.value.requestGeneration)
        coVerify(exactly = 2) { repository.getRepos(any(), any(), any(), any()) }
    }

    @Test
    fun `切换筛选取消防抖立即查询并保留主体标题`() = runTest {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("old")))
        val pending = CompletableDeferred<ApiResult<RepoList>>()
        val filters = RepoFilters(query = "new", visibility = RepoVisibility.PRIVATE)
        coEvery { repository.getRepos(null, null, filters, false) } coAnswers { pending.await() }
        val vm = viewModel()
        vm.init(null)
        vm.onScrolledToTop(vm.state.value.requestGeneration)
        vm.updateSearch("new")
        vm.selectVisibility(RepoVisibility.PRIVATE)
        coVerify(exactly = 1) { repository.getRepos(null, null, filters, false) }
        assertTrue(vm.state.value.repos.isEmpty())
        assertEquals(0, vm.state.value.completedBatches)
        assertEquals(null, vm.state.value.endCursor)
        assertEquals("me", vm.state.value.ownerContext?.login)
        assertTrue(vm.state.value.scrollToTop)
        pending.complete(ApiResult.Success(page()))
        advanceTimeBy(300)
        runCurrent()
        coVerify(exactly = 0) { repository.getRepos(null, null, RepoFilters(query = "new"), false) }
    }

    @Test
    fun `迟到成功与迟到失败均不能覆盖新查询或发错误`() = runTest {
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { errors.events.collect { events += it } }
        for (late in listOf(ApiResult.Success(page(repo("stale"))), ApiResult.Failure(ApiError.NetworkError))) {
            lateinit var completion: Continuation<ApiResult<RepoList>>
            coEvery { repository.getRepos(null, null, RepoFilters(query = "old"), false) } coAnswers {
                suspendCoroutine { completion = it }
            }
            coEvery { repository.getRepos(null, null, RepoFilters(query = "new"), false) } returns ApiResult.Success(page(repo("new")))
            val vm = viewModel()
            vm.init(null)
            vm.updateSearch("old")
            advanceTimeBy(300)
            runCurrent()
            vm.updateSearch("new")
            advanceTimeBy(300)
            runCurrent()
            completion.resume(late)
            runCurrent()
            assertEquals("new", vm.state.value.repos.single().name)
            assertFalse(vm.state.value.loadFailed)
            assertTrue(events.isEmpty())
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `刷新隔离在途翻页并禁止同代次并发追加`() = runTest {
        lateinit var completion: Continuation<ApiResult<RepoList>>
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("old"), next = "c1"))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } coAnswers { suspendCoroutine { completion = it } }
        coEvery { repository.getRepos(null, null, RepoFilters(), true) } returns ApiResult.Success(page(repo("fresh"), next = "fresh"))
        val vm = viewModel()
        vm.init(null)
        vm.loadMore()
        vm.loadMore()
        coVerify(exactly = 1) { repository.getRepos(null, "c1", RepoFilters(), false) }
        vm.refresh()
        completion.resume(ApiResult.Success(page(repo("late"))))
        runCurrent()
        assertEquals("fresh", vm.state.value.repos.single().name)
        assertEquals("fresh", vm.state.value.endCursor)
        assertFalse(vm.state.value.isBusy)
    }

    @Test
    fun `追加失败保留内容并暂停自动请求重试从失败批起点继续`() = runTest {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("old"), next = "c1"))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } returns
            ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(page(repo("new")))
        val vm = viewModel()
        vm.init(null)
        vm.loadMore()
        assertEquals("old", vm.state.value.repos.single().name)
        assertEquals(1, vm.state.value.completedBatches)
        assertEquals("c1", vm.state.value.endCursor)
        assertTrue(vm.state.value.loadMoreFailed)
        assertFalse(vm.state.value.canAutoLoadMore)
        vm.loadMore()
        coVerify(exactly = 1) { repository.getRepos(null, "c1", RepoFilters(), false) }
        vm.retry()
        assertEquals(2, vm.state.value.repos.size)
        assertEquals(2, vm.state.value.completedBatches)
        assertFalse(vm.state.value.loadMoreFailed)
    }

    @Test
    fun `连续空批次必须逐次手动继续直到最后一页`() = runTest {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(next = "c1", manual = true))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } returns ApiResult.Success(page(next = "c2", manual = true))
        coEvery { repository.getRepos(null, "c2", RepoFilters(), false) } returns ApiResult.Success(page(repo("found")))
        val vm = viewModel()
        vm.init(null)
        vm.loadMore()
        coVerify(exactly = 1) { repository.getRepos(any(), any(), any(), any()) }
        vm.continueSearch()
        assertEquals(2, vm.state.value.completedBatches)
        assertEquals("c2", vm.state.value.endCursor)
        assertTrue(vm.state.value.requiresManualContinue)
        vm.loadMore()
        coVerify(exactly = 2) { repository.getRepos(any(), any(), any(), any()) }
        vm.continueSearch()
        assertEquals("found", vm.state.value.repos.single().name)
        assertEquals(3, vm.state.value.completedBatches)
        assertFalse(vm.state.value.hasNextPage)
    }

    @Test
    fun `仅重复节点的批次暂停自动翻页并保留最新节点内容`() = runTest {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("same"), next = "c1"))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } returns ApiResult.Success(page(repo("SAME").copy(stargazerCount = 9), next = "c2"))
        val vm = viewModel()
        vm.init(null)
        vm.loadMore()
        assertEquals(1, vm.state.value.repos.size)
        assertEquals(9, vm.state.value.repos.single().stargazerCount)
        assertTrue(vm.state.value.requiresManualContinue)
        vm.loadMore()
        coVerify(exactly = 0) { repository.getRepos(null, "c2", any(), any()) }
    }

    @Test
    fun `SavedStateHandle恢复六项条件清空只清词重置保留主体`() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.init("me")
        vm.updateSearch(" Raw Query ")
        vm.selectVisibility(RepoVisibility.INTERNAL)
        vm.selectArchived(false)
        vm.selectFork(true)
        vm.selectIssues(false)
        vm.selectSort(RepoSort.NAME_DESC)
        vm.onScrolledToTop(vm.state.value.requestGeneration)
        val expected = vm.state.value.filters
        val restored = viewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals(expected, restored.state.value.filters)
        assertTrue(restored.state.value.scrollToTop)
        assertFalse(restored.state.value.hasLoaded)
        restored.init("me")
        coVerify(exactly = 2) { repository.getRepos("me", null, expected.normalized(), false) }
        restored.clearSearch()
        assertEquals(expected.copy(query = ""), restored.state.value.filters)
        restored.reset()
        assertEquals(RepoFilters(), restored.state.value.filters)
        assertEquals("me", restored.state.value.ownerContext?.login)
    }

    @Test
    fun `重置四维及排序保留原始搜索词并持久化重复重置不请求`() = runTest {
        val query = "  Raw Query  "
        val handle = SavedStateHandle(mapOf(
            "repos.query" to query, "repos.visibility" to RepoVisibility.PRIVATE.name,
            "repos.archived" to true, "repos.fork" to true, "repos.issues" to false,
            "repos.sort" to RepoSort.NAME_DESC.name,
        ))
        val vm = viewModel(handle)
        vm.init("me")
        vm.onScrolledToTop(vm.state.value.requestGeneration)

        vm.reset()

        val expected = RepoFilters(query = query)
        assertEquals(expected, vm.state.value.filters)
        assertEquals("me", vm.state.value.ownerContext?.login)
        assertTrue(vm.state.value.scrollToTop)
        assertEquals(expected, viewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })).state.value.filters)
        coVerify(exactly = 1) { repository.getRepos("me", null, expected.normalized(), false) }
        val generation = vm.state.value.requestGeneration
        vm.reset()
        assertEquals(generation, vm.state.value.requestGeneration)
        coVerify(exactly = 2) { repository.getRepos(any(), any(), any(), any()) }
    }

    @Test
    fun `主体改变恢复新入口默认值并隔离旧响应`() = runTest {
        lateinit var completion: Continuation<ApiResult<RepoList>>
        coEvery { repository.getRepos("old", null, any(), false) } coAnswers { suspendCoroutine { completion = it } }
        coEvery { repository.getRepos("new", null, RepoFilters(), false) } returns ApiResult.Success(page(repo("new"), owner = "new"))
        val vm = viewModel(SavedStateHandle(mapOf("repos.query" to "old query")))
        vm.init("old")
        vm.init("new")
        completion.resume(ApiResult.Success(page(repo("old"), owner = "old")))
        runCurrent()
        assertEquals(RepoFilters(), vm.state.value.filters)
        assertEquals("new", vm.state.value.ownerContext?.login)
        assertEquals("new", vm.state.value.repos.single().name)
    }

    @Test
    fun `星标与议题事件只同步相应字段且议题数量不改变功能开关`() = runTest {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("one")))
        val vm = viewModel()
        vm.init(null)
        bus.emit(RepoUpdateEvent.StarChanged("ME", "ONE", true, 99))
        bus.emit(RepoUpdateEvent.IssueCountChanged("me", "one", 0))
        runCurrent()
        assertEquals(99, vm.state.value.repos.single().stargazerCount)
        assertEquals(0, vm.state.value.repos.single().openIssueCount)
        assertTrue(vm.state.value.repos.single().hasIssuesEnabled)
        verify(exactly = 1) { repository.invalidateAllCaches() }
        coVerify(exactly = 1) { repository.getRepos(any(), any(), any(), any()) }
    }

    @Test
    fun `星标排序下先重排卡片再刷新第一批`() = runTest {
        val filters = RepoFilters(sort = RepoSort.STARS_ASC)
        val updated = CompletableDeferred<ApiResult<RepoList>>()
        coEvery { repository.getRepos(null, null, filters, false) } returns ApiResult.Success(page(repo("a").copy(stargazerCount = 1), repo("b").copy(stargazerCount = 2)))
        coEvery { repository.getRepos(null, null, filters, true) } coAnswers { updated.await() }
        val vm = viewModel(SavedStateHandle(mapOf("repos.sort" to RepoSort.STARS_ASC.name)))
        vm.init(null)
        bus.emit(RepoUpdateEvent.StarChanged("me", "a", true, 9))
        runCurrent()
        assertEquals(listOf("b", "a"), vm.state.value.repos.map { it.name })
        assertTrue(vm.state.value.isRefreshing)
        updated.complete(ApiResult.Success(page(*vm.state.value.repos.toTypedArray())))
        runCurrent()
        assertFalse(vm.state.value.isRefreshing)
        coVerify(exactly = 1) { repository.getRepos(null, null, filters, true) }
    }

    @Test
    fun `在途分页收到星标事件时刷新以阻止旧计数覆盖`() = runTest {
        lateinit var completion: Continuation<ApiResult<RepoList>>
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("one"), next = "c1"))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } coAnswers { suspendCoroutine { completion = it } }
        coEvery { repository.getRepos(null, null, RepoFilters(), true) } returns ApiResult.Success(page(repo("one").copy(stargazerCount = 9)))
        val vm = viewModel()
        vm.init(null)
        vm.loadMore()
        bus.emit(RepoUpdateEvent.StarChanged("me", "one", true, 9))
        runCurrent()
        completion.resume(ApiResult.Success(page(repo("one"))))
        runCurrent()
        assertEquals(9, vm.state.value.repos.single().stargazerCount)
        assertFalse(vm.state.value.hasNextPage)
    }

    @Test
    fun `相关仓库变更事件按当前条件刷新不相关事件忽略`() = runTest {
        val filters = RepoFilters(query = "needle", isArchived = false)
        val vm = viewModel(SavedStateHandle(mapOf("repos.query" to "needle", "repos.archived" to false)))
        vm.init("me")
        val changes = listOf(
            RepoUpdateEvent.RepositoryCreated("me", "new"),
            RepoUpdateEvent.RepositoryChanged("me", "repo", mockk()),
            RepoUpdateEvent.RepositoryDeleted("me", "repo"),
            RepoUpdateEvent.RepositoryAddressChanged("me", "repo", "other", "renamed"),
            RepoUpdateEvent.RepositoryAddressChanged("other", "repo", "me", "received"),
            RepoUpdateEvent.CodeChanged("me", "repo", "main", "oid", emptyList()),
            RepoUpdateEvent.BranchSynced("me", "repo", "main"),
        )
        changes.forEach { bus.emit(it) }
        bus.emit(RepoUpdateEvent.RepositoryCreated("unrelated", "repo"))
        bus.emit(RepoUpdateEvent.BranchSwitched("me", "repo", "other"))
        runCurrent()
        assertEquals(filters, vm.state.value.filters)
        coVerify(exactly = changes.size) { repository.getRepos("me", null, filters, true) }
        verify(exactly = changes.size) { repository.invalidateAllCaches() }
    }

    private fun repo(name: String) = RepoListItem(
        name = name, ownerLogin = "me", description = null, homepageUrl = null,
        visibility = RepoVisibility.PUBLIC, isArchived = false, isFork = false, hasIssuesEnabled = true,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, pushedAt = null,
        forkedFromOwner = null, forkedFromName = null, languageName = null, languageColor = null,
        stargazerCount = 0, forkCount = 0, openIssueCount = 1, topics = emptyList(), defaultBranchName = "main",
    )

    private fun page(
        vararg repos: RepoListItem,
        next: String? = null,
        total: Int = repos.size,
        manual: Boolean = false,
        owner: String = "me",
    ) = RepoList(
        total, repos.toList(), next != null, next,
        RepositoryListOwnerContext(RepositoryCreateOwner("u1", owner, "Owner", null, RepositoryCreateOwnerType.USER, true)),
        requiresManualContinue = manual,
    )
}
