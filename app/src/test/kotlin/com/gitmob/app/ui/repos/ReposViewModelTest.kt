package com.gitmob.app.ui.repos

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *   - 不通过 Hilt，直接 new ViewModel(...)，注入 Mock Repository
 *
 * 覆盖：
 *   - 初次加载（viewer，login=null）成功：repos/totalCount 填充，loading 复位
 *   - 初次加载失败 → retry 成功（失败恢复）
 *   - refresh 重置游标
 *   - 分页：hasNextPage=true loadMore 追加，hasNextPage=false 不再追加
 *   - 跨 VM 事件：StarChanged 时同步把对应卡片的 stargazerCount 更新到新值
 *   - 并发：refresh + loadMore 顺序执行，不崩
 */
class ReposViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeRepos(prefix: String, count: Int, hasNext: Boolean = false) = RepoList(
        totalCount = count,
        items = (1..count).map { i ->
            RepoListItem(
                name = "$prefix-r$i", ownerLogin = "me",
                description = null, homepageUrl = null,
                isPrivate = false, isArchived = false, isFork = false,
                forkedFromOwner = null, forkedFromName = null,
                languageName = "Kotlin", languageColor = "#A97BFF",
                stargazerCount = i * 2, forkCount = 0, openIssueCount = 0,
                topics = emptyList(), defaultBranchName = "main",
            )
        },
        hasNextPage = hasNext,
        endCursor = if (hasNext) "c1" else null,
    )

    @Test
    fun `初始化加载viewer仓库列表成功时填充repos和totalCount`() = runTest {
        val repoRepo = mockk<RepoRepository>()
        val expected = fakeRepos("a", 7)
        coEvery { repoRepo.getRepos(login = null, after = null) } returns ApiResult.Success(expected)

        val vm = ReposViewModel(repoRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.init(login = null)

        assertFalse(vm.state.value.isLoading)
        assertEquals(7, vm.state.value.repos.size)
        assertEquals(7, vm.state.value.totalCount)
        assertFalse(vm.state.value.loadFailed)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `初次加载失败时loadFailed为true retry成功`() = runTest {
        val repoRepo = mockk<RepoRepository>()
        coEvery { repoRepo.getRepos(login = null, after = null) } returns
                ApiResult.Failure(ApiError.NetworkError) andThen
                ApiResult.Success(fakeRepos("b", 2))

        val vm = ReposViewModel(repoRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.init(login = null)
        assertTrue(vm.state.value.loadFailed)
        assertEquals(0, vm.state.value.repos.size)

        vm.retry()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(2, vm.state.value.repos.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `hasNextPage为true时loadMore追加repos列表`() = runTest {
        val repoRepo = mockk<RepoRepository>()
        val page1 = fakeRepos("p1", 3, hasNext = true)
        val page2 = fakeRepos("p2", 2, hasNext = false)
        coEvery { repoRepo.getRepos(login = null, after = null) } returns ApiResult.Success(page1)
        coEvery { repoRepo.getRepos(login = null, after = "c1") } returns ApiResult.Success(page2)

        val vm = ReposViewModel(repoRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.init(login = null)
        assertEquals(3, vm.state.value.repos.size)
        assertTrue(vm.state.value.hasNextPage)

        vm.loadMore()
        assertEquals(5, vm.state.value.repos.size)
        assertFalse(vm.state.value.hasNextPage)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `refresh后isRefreshing复位列表仍有内容`() = runTest {
        val repoRepo = mockk<RepoRepository>()
        coEvery { repoRepo.getRepos(login = null, after = null) } returns
                ApiResult.Success(fakeRepos("cached", 2))
        coEvery { repoRepo.getViewerReposFresh() } returns
                ApiResult.Success(fakeRepos("fresh", 3))

        val vm = ReposViewModel(repoRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.init(login = null)
        vm.refresh()
        assertFalse(vm.state.value.isRefreshing)
        assertEquals(3, vm.state.value.repos.size)
        assertTrue(vm.state.value.repos.first().name.startsWith("fresh"))
        coVerify(exactly = 1) { repoRepo.getViewerReposFresh() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `他人仓库refresh继续按login直接请求而不调用viewerFresh`() = runTest {
        val repoRepo = mockk<RepoRepository>()
        coEvery { repoRepo.getRepos(login = "octocat", after = null) } returns
                ApiResult.Success(fakeRepos("initial", 1)) andThen
                ApiResult.Success(fakeRepos("refreshed", 2))

        val vm = ReposViewModel(repoRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.init(login = "octocat")
        vm.refresh()

        assertEquals(2, vm.state.value.repos.size)
        assertTrue(vm.state.value.repos.first().name.startsWith("refreshed"))
        coVerify(exactly = 2) { repoRepo.getRepos(login = "octocat", after = null) }
        coVerify(exactly = 0) { repoRepo.getViewerReposFresh() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `跨VM事件_StarChanged同步修改对应仓库的stargazerCount`() = runTest {
        val repoRepo = mockk<RepoRepository>()
        val bus = RepoUpdateEventBus()
        val list = fakeRepos("star", 2)
        // items[0]: stargazerCount=2, items[1]: stargazerCount=4
        coEvery { repoRepo.getRepos(login = null, after = null) } returns ApiResult.Success(list)

        val vm = ReposViewModel(repoRepo, bus, ErrorEventBus())
        vm.init(login = null)
        assertEquals(2, vm.state.value.repos.first().stargazerCount)

        bus.emit(
            RepoUpdateEvent.StarChanged(
                owner = "me", name = "star-r1", isStarred = true, stargazerCount = 99,
            ),
        )
        // collect 是在 viewModelScope.launch 跑的，协程需要点时间进入 collect body
        kotlinx.coroutines.delay(20)
        assertEquals(99, vm.state.value.repos.first().stargazerCount)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `并发场景_refresh加loadMore不崩溃且状态最终一致`() = runTest {
        val repoRepo = mockk<RepoRepository>()
        val page1 = fakeRepos("q", 3, hasNext = true)
        val page2 = fakeRepos("q", 1, hasNext = false)
        coEvery { repoRepo.getRepos(login = null, after = null) } returns ApiResult.Success(page1)
        coEvery { repoRepo.getViewerReposFresh() } returns ApiResult.Success(page1)
        coEvery { repoRepo.getRepos(login = null, after = "c1") } returns ApiResult.Success(page2)

        val vm = ReposViewModel(repoRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.init(login = null)
        vm.refresh()
        vm.loadMore()
        assertFalse(vm.state.value.isLoadingMore)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.isRefreshing)
        vm.viewModelScope.cancel()
    }
}
