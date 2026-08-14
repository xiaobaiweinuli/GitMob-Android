package com.gitmob.app.ui.stars

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.PagedStarredRepos
import com.gitmob.app.data.model.StarFilter
import com.gitmob.app.data.model.StarredRepo
import com.gitmob.app.data.model.UserListSummary
import com.gitmob.app.data.repository.StarRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 *   - 初次加载成功：lists + repos 填充，totalCount 正确，loading 复位
 *   - 初次加载失败 → retry 成功（失败恢复）
 *   - refresh 重置游标并重新请求 lists 和 repos
 *   - selectFilter 切换 All/ByList：清空当前 repos 并重新加载对应数据源
 *   - hasNextPage=true 时 loadMore 追加
 *   - unstarRepo 成功：本地从 repos 里移除并广播 StarChanged 事件
 *   - 跨 VM 事件：收到 StarChanged(isStarred=false) 时从本页移除对应条目
 *   - 并发：refresh + loadMore 顺序执行不崩
 */
class StarsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeLists() = listOf(
        UserListSummary(id = "L1", name = "Work", slug = "work", description = null, isPrivate = false, itemCount = 3),
        UserListSummary(id = "L2", name = "Fun", slug = "fun", description = null, isPrivate = true, itemCount = 7),
    )

    private fun fakeStarred(prefix: String, count: Int, hasNext: Boolean = false) = PagedStarredRepos(
        totalCount = count,
        items = (1..count).map { i ->
            StarredRepo(
                id = "$prefix-$i", name = "repo$i", url = "https://github.com/$prefix/repo$i",
                description = null, homepageUrl = null,
                ownerLogin = prefix, ownerAvatarUrl = null,
                isPrivate = false, isArchived = false,
                languageName = "Kotlin", languageColor = "#A97BFF",
                stargazerCount = i * 10, forkCount = 0, openIssueCount = 0,
                topics = emptyList(), defaultBranchName = "main",
            )
        },
        hasNextPage = hasNext,
        endCursor = if (hasNext) "cur" else null,
    )

    @Test
    fun `初次加载成功时填充lists和repos以及totalCount`() = runTest {
        val starRepo = mockk<StarRepository>()
        coEvery { starRepo.getLists() } returns ApiResult.Success(fakeLists())
        coEvery { starRepo.getViewerStarred(after = null) } returns ApiResult.Success(fakeStarred("a", 5))

        val vm = StarsViewModel(starRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.loadIfNeeded()

        assertFalse(vm.state.value.isLoading)
        assertEquals(2, vm.state.value.lists.size)
        assertEquals(5, vm.state.value.repos.size)
        assertEquals(5, vm.state.value.totalCount)
        assertFalse(vm.state.value.loadFailed)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `初次加载失败时loadFailed为true retry成功`() = runTest {
        val starRepo = mockk<StarRepository>()
        coEvery { starRepo.getLists() } returns ApiResult.Success(fakeLists())
        coEvery { starRepo.getViewerStarred(after = null) } returns
                ApiResult.Failure(ApiError.NetworkError) andThen
                ApiResult.Success(fakeStarred("b", 2))

        val vm = StarsViewModel(starRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.loadIfNeeded()
        assertTrue(vm.state.value.loadFailed)
        assertEquals(0, vm.state.value.repos.size)

        vm.retry()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(2, vm.state.value.repos.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `selectFilter切换时清空repos并重载对应筛选源`() = runTest {
        val starRepo = mockk<StarRepository>()
        val allPage = fakeStarred("all", 3)
        val listPage = fakeStarred("list", 2)
        coEvery { starRepo.getLists() } returns ApiResult.Success(fakeLists())
        coEvery { starRepo.getViewerStarred(after = null) } returns ApiResult.Success(allPage)
        coEvery { starRepo.getListItems("L1", null) } returns ApiResult.Success(listPage)

        val vm = StarsViewModel(starRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.loadIfNeeded()
        val allSize = vm.state.value.repos.size
        assertTrue(vm.state.value.selectedFilter is StarFilter.All)

        vm.selectFilter(StarFilter.ByList(fakeLists().first()))
        assertTrue(vm.state.value.selectedFilter is StarFilter.ByList)
        // 切换后 repos 大小应该等于按 list 查询的数量
        assertEquals(listPage.items.size, vm.state.value.repos.size)
        // 切换到同样的 filter 时（重复选 ByList）不会再发请求（因为 selectFilter 有 early return 判等）
        vm.selectFilter(StarFilter.ByList(fakeLists().first()))
        assertEquals(listPage.items.size, vm.state.value.repos.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `hasNextPage为true时loadMore追加repos`() = runTest {
        val starRepo = mockk<StarRepository>()
        val p1 = fakeStarred("p1", 3, hasNext = true)
        val p2 = fakeStarred("p2", 2, hasNext = false)
        coEvery { starRepo.getLists() } returns ApiResult.Success(emptyList())
        coEvery { starRepo.getViewerStarred(after = null) } returns ApiResult.Success(p1)
        coEvery { starRepo.getViewerStarred(after = "cur") } returns ApiResult.Success(p2)

        val vm = StarsViewModel(starRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.loadIfNeeded()
        assertEquals(3, vm.state.value.repos.size)
        assertTrue(vm.state.value.hasNextPage)

        vm.loadMore()
        assertEquals(5, vm.state.value.repos.size)
        assertFalse(vm.state.value.hasNextPage)
        coVerify(exactly = 1) { starRepo.getViewerStarred(after = "cur") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `unstarRepo成功时从repos里移除对应条目并广播StarChanged`() = runTest {
        val starRepo = mockk<StarRepository>()
        val bus = RepoUpdateEventBus()
        coEvery { starRepo.getLists() } returns ApiResult.Success(emptyList())
        coEvery { starRepo.getViewerStarred(after = null) } returns ApiResult.Success(fakeStarred("u", 3))
        coEvery { starRepo.unstarRepo(any()) } returns ApiResult.Success(Unit)

        val vm = StarsViewModel(starRepo, bus, ErrorEventBus())
        vm.loadIfNeeded()
        val beforeSize = vm.state.value.repos.size // 3
        val target = vm.state.value.repos[1] // id=u-2, name=repo2, ownerLogin=u
        val expectedCountAfter = (target.stargazerCount - 1).coerceAtLeast(0)

        bus.events.test {
            vm.unstarRepo(target)
            assertEquals(beforeSize - 1, vm.state.value.repos.size)
            assertFalse(vm.state.value.repos.any { it.id == target.id })

            // expectMostRecentItem 拿到最新的事件
            val latest = expectMostRecentItem()
            if (latest is RepoUpdateEvent.StarChanged) {
                assertEquals("u", latest.owner)
                assertEquals("repo2", latest.name)
                assertFalse(latest.isStarred)
                assertEquals(expectedCountAfter, latest.stargazerCount)
            } else {
                // 如果不是 StarChanged，说明顺序不对（unstarRepo 之前 ViewModel init 里 collect 的事件先到了），那等下一个
                val next = awaitItem()
                assertTrue(next is RepoUpdateEvent.StarChanged)
                val evt = next as RepoUpdateEvent.StarChanged
                assertEquals("u", evt.owner)
                assertEquals("repo2", evt.name)
                assertFalse(evt.isStarred)
                assertEquals(expectedCountAfter, evt.stargazerCount)
            }
            cancelAndIgnoreRemainingEvents()
        }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `跨VM事件_收到StarChanged取消星标时从repos移除对应条目`() = runTest {
        val starRepo = mockk<StarRepository>()
        val bus = RepoUpdateEventBus()
        coEvery { starRepo.getLists() } returns ApiResult.Success(emptyList())
        coEvery { starRepo.getViewerStarred(after = null) } returns ApiResult.Success(fakeStarred("v", 3))

        val vm = StarsViewModel(starRepo, bus, ErrorEventBus())
        vm.loadIfNeeded()
        assertEquals(3, vm.state.value.repos.size)

        // 发一个"取消星标"事件 → StarsViewModel 的 StarChanged 分支里 isStarred=false 会 filterNot 掉
        bus.emit(
            RepoUpdateEvent.StarChanged(
                owner = "v", name = "repo2", isStarred = false, stargazerCount = 10,
            ),
        )
        kotlinx.coroutines.delay(20)
        assertEquals(2, vm.state.value.repos.size)
        assertFalse(vm.state.value.repos.any { it.ownerLogin == "v" && it.name == "repo2" })
        vm.viewModelScope.cancel()
    }

    @Test
    fun `并发场景_refresh加loadMore不崩溃`() = runTest {
        val starRepo = mockk<StarRepository>()
        coEvery { starRepo.getLists() } returns ApiResult.Success(fakeLists())
        coEvery { starRepo.getListsFresh() } returns ApiResult.Success(fakeLists())
        coEvery { starRepo.getViewerStarred(after = null) } returns
                ApiResult.Success(fakeStarred("x", 3, hasNext = true))
        coEvery { starRepo.getAllViewerStarredFresh() } returns
                ApiResult.Success(fakeStarred("fresh", 3, hasNext = true))
        coEvery { starRepo.getViewerStarred(after = "cur") } returns
                ApiResult.Success(fakeStarred("y", 1, hasNext = false))

        val vm = StarsViewModel(starRepo, RepoUpdateEventBus(), ErrorEventBus())
        vm.loadIfNeeded()
        vm.refresh()
        vm.loadMore()
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.isRefreshing)
        assertFalse(vm.state.value.isLoadingMore)
        coVerify(exactly = 1) { starRepo.getListsFresh() }
        coVerify(exactly = 1) { starRepo.getAllViewerStarredFresh() }
        vm.viewModelScope.cancel()
    }
}
