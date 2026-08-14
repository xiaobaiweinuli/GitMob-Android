package com.gitmob.app.ui.repodetail

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepoReadme
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.StarRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
class RepoDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeDetail(
        viewerHasStarred: Boolean = false,
        stargazerCount: Int = 42,
        branch: String = "main",
    ) = RepoDetail(
        id = "R_123", name = "greetings", ownerLogin = "octo", ownerAvatarUrl = null,
        description = null, homepageUrl = null,
        isPrivate = false, isArchived = false, isTemplate = false, isFork = false,
        forkedFromOwner = null, forkedFromName = null,
        stargazerCount = stargazerCount, viewerHasStarred = viewerHasStarred,
        forkCount = 1, openIssueCount = 2, openPrCount = 3, watcherCount = 4,
        viewerSubscription = "UNSUBSCRIBED",
        licenseName = null, licenseSpdxId = null,
        branchCount = 1, defaultBranchName = branch,
        releaseCount = 0, latestReleaseName = null, latestReleaseTag = null,
        languageName = "Java", languageColor = "#b07219",
        topics = emptyList(),
        capabilities = RepoCapabilities.NONE,
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

        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), md, RepoUpdateEventBus(), ErrorEventBus())
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

        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus())
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

        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus())
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
        val bus = RepoUpdateEventBus()

        val vm = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), bus, ErrorEventBus())
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

        val vm = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus())
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
        val vm = RepoDetailViewModel(detailRepo, mockk(), mockk(), md, bus, ErrorEventBus())
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

        val vm = RepoDetailViewModel(detailRepo, mockk(), starRepo, makeFakeRenderer(), RepoUpdateEventBus(), ErrorEventBus())
        vm.init("c", "d")
        vm.toggleStar()
        vm.retry()
        vm.toggleStar()
        assertFalse(vm.state.value.isLoading)
        vm.viewModelScope.cancel()
    }
}
