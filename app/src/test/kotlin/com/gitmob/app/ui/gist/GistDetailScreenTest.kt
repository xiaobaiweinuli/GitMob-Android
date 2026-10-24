package com.gitmob.app.ui.gist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.model.GistDetail
import com.gitmob.app.data.model.GistFile
import com.gitmob.app.data.model.GistForkSource
import com.gitmob.app.data.model.GistUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GistDetailScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `折叠文件仍显示三点菜单但会关闭已展开菜单`() {
        val file = GistFile("main.txt", "text/plain", "Text", "https://example/raw", 5, false, "hello")
        composeRule.setContent {
            MaterialTheme {
                val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val menu = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                GistFileCard(
                    file = file,
                    expanded = expanded.value,
                    menuExpanded = menu.value,
                    canEdit = true,
                    canDelete = true,
                    onToggle = { expanded.value = !expanded.value; if (!expanded.value) menu.value = false },
                    onMenuChange = { menu.value = it },
                    onEdit = {},
                    onDelete = {},
                    onDownload = {},
                    onShare = {},
                )
            }
        }
        val edit = context.getString(R.string.common_edit)
        composeRule.onNodeWithText("main.txt").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_more)).performClick()
        composeRule.onNodeWithText(edit).assertExists()

        composeRule.onNodeWithText("main.txt").performClick()

        composeRule.onNodeWithText(edit).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_more)).assertExists()
    }

    @Test
    fun `他人Gist显示星标与复刻计数入口`() {
        val detail = detail(owner = "octocat", commentsEnabled = false)
        composeRule.setContent {
            MaterialTheme {
                GistDetailContent(
                    detail = detail,
                    state = GistDetailUiState(detail = detail),
                    onOwnerClick = {},
                    onForkSourceClick = {},
                    onRevisions = {},
                    onForks = {},
                    onStargazers = {},
                    onToggleStar = {},
                    onFork = {},
                    onAddFile = {},
                    onEditFile = {},
                    onDeleteFile = {},
                    onDownloadFile = {},
                    onShareFile = {},
                    onEditComment = {},
                    onDeleteComment = {},
                    onQuoteComment = {},
                    onEditHistory = {},
                    onLoadMoreComments = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.gist_star)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.gist_fork)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.gist_stargazers_count, 3)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.gist_forks_count, 2)).assertExists()

        val labels = listOf(
            context.getString(R.string.gist_stargazers_count, 3),
            context.getString(R.string.gist_forks_count, 2),
            context.getString(R.string.gist_star),
            context.getString(R.string.gist_fork),
        )
        val leadingEdges = labels.take(3).map { composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertTrue(leadingEdges.zipWithNext().all { (left, right) -> left < right })
        composeRule.onNodeWithText(labels.last()).performScrollTo()
        val starLeft = composeRule.onNodeWithText(labels[2]).fetchSemanticsNode().boundsInRoot.left
        val forkLeft = composeRule.onNodeWithText(labels[3]).fetchSemanticsNode().boundsInRoot.left
        assertTrue(starLeft < forkLeft)
    }

    @Test
    fun `复刻来源位于描述上方并跳转来源Gist`() {
        val source = GistForkSource(
            id = "source-id",
            owner = GistUser("octocat", null, null),
            url = "https://gist.github.com/octocat/source-id",
        )
        val detail = detail(owner = "viewer", commentsEnabled = false).copy(forkOf = source)
        var openedSource: String? = null
        composeRule.setContent {
            MaterialTheme {
                GistDetailContent(
                    detail = detail,
                    state = GistDetailUiState(detail = detail),
                    onOwnerClick = {},
                    onForkSourceClick = { openedSource = it },
                    onRevisions = {},
                    onForks = {},
                    onStargazers = {},
                    onToggleStar = {},
                    onFork = {},
                    onAddFile = {},
                    onEditFile = {},
                    onDeleteFile = {},
                    onDownloadFile = {},
                    onShareFile = {},
                    onEditComment = {},
                    onDeleteComment = {},
                    onQuoteComment = {},
                    onEditHistory = {},
                    onLoadMoreComments = {},
                )
            }
        }

        val sourceText = context.getString(R.string.gist_forked_from, "octocat")
        val sourceNode = composeRule.onNodeWithText(sourceText)
        assertTrue(
            sourceNode.fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNodeWithText("sample").fetchSemanticsNode().boundsInRoot.top,
        )
        sourceNode.performClick()

        assertEquals("source-id", openedSource)
        composeRule.onNodeWithText("source-id").assertDoesNotExist()
    }

    private fun detail(owner: String, commentsEnabled: Boolean) = GistDetail(
        id = "abc",
        nodeId = "node",
        url = "https://gist.github.com/$owner/abc",
        description = "sample",
        isPublic = true,
        owner = GistUser(owner, null, null),
        files = listOf(GistFile("main.txt", "text/plain", "Text", "https://example/raw", 5, false, "hello")),
        commentCount = 0,
        commentsEnabled = commentsEnabled,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        forkOf = null,
        latestVersion = "sha",
        viewerLogin = "viewer",
        isStarred = false,
        stargazerCount = 3,
        forkCount = 2,
    )
}
