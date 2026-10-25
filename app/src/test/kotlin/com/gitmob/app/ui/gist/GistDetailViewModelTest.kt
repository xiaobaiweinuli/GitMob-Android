package com.gitmob.app.ui.gist

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.data.model.GistComment
import com.gitmob.app.data.model.GistCommentPage
import com.gitmob.app.data.model.GistDetail
import com.gitmob.app.data.model.GistFile
import com.gitmob.app.data.model.GistUser
import com.gitmob.app.data.repository.ConversationEditRepository
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GistDetailViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<GistRepository>()
    private val editRepository = mockk<ConversationEditRepository>()
    private val renderer = mockk<MarkdownRenderer>()
    private val errorBus = ErrorEventBus()
    private val shareLauncher = mockk<ShareLinkLauncher>(relaxed = true)
    private val downloadLauncher = mockk<ExternalDownloadLauncher>(relaxed = true)

    @Test
    fun `加载详情并为REST纯文本评论生成HTML`() = runTest {
        coEvery { repository.getGist("abc") } returns ApiResult.Success(detail())
        coEvery { repository.getComments("abc", 1) } returns ApiResult.Success(
            GistCommentPage(listOf(comment(bodyHtml = "")), null),
        )
        every { renderer.renderToHtml("hello") } returns "<p>hello</p>"
        val viewModel = viewModel()

        viewModel.init("abc")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.detail?.isOwnedByViewer == true)
        assertEquals("<p>hello</p>", viewModel.state.value.comments.single().bodyHtml)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `他人Gist切换星标只更新星标状态`() = runTest {
        coEvery { repository.getGist("abc") } returns ApiResult.Success(detail(owner = "octocat", stargazerCount = 3))
        coEvery { repository.getComments("abc", 1) } returns ApiResult.Success(GistCommentPage(emptyList(), null))
        coEvery { repository.setStarred("abc", true) } returns ApiResult.Success(true)
        val viewModel = viewModel()
        viewModel.init("abc")
        advanceUntilIdle()

        viewModel.toggleStar()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.detail?.isStarred == true)
        assertEquals(4, viewModel.state.value.detail?.stargazerCount)
        coVerify(exactly = 1) { repository.setStarred("abc", true) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `分页失败保留已有评论并发送统一错误`() = runTest {
        coEvery { repository.getGist("abc") } returns ApiResult.Success(detail())
        coEvery { repository.getComments("abc", 1) } returns ApiResult.Success(GistCommentPage(listOf(comment()), 2))
        coEvery { repository.getComments("abc", 2) } returns ApiResult.Failure(ApiError.NetworkError)
        val viewModel = viewModel()
        viewModel.init("abc")
        advanceUntilIdle()
        val event = async(start = CoroutineStart.UNDISPATCHED) { errorBus.events.first() }
        viewModel.loadMoreComments()
        advanceUntilIdle()

        assertEquals(BannerEvent.Error(ApiError.NetworkError), event.await())
        assertEquals(1, viewModel.state.value.comments.size)
        assertFalse(viewModel.state.value.isLoadingMoreComments)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `ZIP下载使用最早修订SHA`() = runTest {
        coEvery { repository.getGist("abc") } returns ApiResult.Success(detail())
        coEvery { repository.getComments("abc", 1) } returns ApiResult.Success(GistCommentPage(emptyList(), null))
        val viewModel = viewModel(oldestVersion = "oldest")

        viewModel.init("abc")
        advanceUntilIdle()
        viewModel.downloadZip()

        io.mockk.verify(exactly = 1) {
            downloadLauncher.open("https://gist.github.com/viewer/abc/archive/oldest.zip")
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `删除Gist成功后通知列表刷新并返回`() = runTest {
        coEvery { repository.getGist("abc") } returns ApiResult.Success(detail())
        coEvery { repository.getComments("abc", 1) } returns ApiResult.Success(GistCommentPage(emptyList(), null))
        coEvery { repository.deleteGist("abc") } returns ApiResult.Success(Unit)
        val updateBus = GistUpdateEventBus()
        val viewModel = viewModel(updateBus = updateBus)
        viewModel.init("abc")
        advanceUntilIdle()
        val changed = async(start = CoroutineStart.UNDISPATCHED) { updateBus.listEvents.first() }
        var returned = false

        viewModel.deleteGist { returned = true }
        advanceUntilIdle()

        changed.await()
        assertTrue(returned)
        coVerify(exactly = 1) { repository.deleteGist("abc") }
        viewModel.viewModelScope.cancel()
    }

    private fun viewModel(
        oldestVersion: String? = "oldest",
        updateBus: GistUpdateEventBus = GistUpdateEventBus(),
    ) = GistDetailViewModel(
        repository = repository,
        editRepository = editRepository,
        markdownRenderer = renderer,
        errorEventBus = errorBus,
        shareLinkLauncher = shareLauncher,
        downloadLauncher = downloadLauncher,
        gistUpdateEventBus = updateBus,
    ).also {
        coEvery { repository.getOldestVersion("abc") } returns ApiResult.Success(oldestVersion)
    }

    private fun detail(owner: String = "viewer", stargazerCount: Int = 0) = GistDetail(
        id = "abc",
        nodeId = "node",
        url = "https://gist.github.com/$owner/abc",
        description = "sample",
        isPublic = true,
        owner = GistUser(owner, null, null),
        files = listOf(GistFile("main.txt", "text/plain", "Text", "https://example/raw", 5, false, "hello")),
        commentCount = 1,
        commentsEnabled = true,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-02T00:00:00Z",
        forkOf = null,
        latestVersion = "sha",
        viewerLogin = "viewer",
        isStarred = false,
        stargazerCount = stargazerCount,
        forkCount = 0,
    )

    private fun comment(bodyHtml: String = "<p>hello</p>") = GistComment(
        id = 1,
        nodeId = "comment-node",
        url = "https://example/comment",
        body = "hello",
        bodyHtml = bodyHtml,
        author = GistUser("viewer", null, null),
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        authorAssociation = com.gitmob.app.data.model.CommentAuthorAssociation.OWNER,
    )

}
