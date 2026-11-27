package com.gitmob.app.ui.repocompare

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.model.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class RepoCompareScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val model = mockk<RepoCompareViewModel>(relaxed = true)
    private val file = RepoChangedFile("image.png", null, RepoChangedFileStatus.MODIFIED, 0, 0, 0, null, null, null, null, null)
    private val comparison = RepoComparison(
        RepoComparisonRefs("o", "r", "a".repeat(40), "o", "r", "b".repeat(40)),
        "ahead", 31, 0, 31,
        (0 until 30).map { RepoCommitSummary("sha$it", "sha$it", "Commit $it", "", null, null, null, null, 0, 0, null, emptyList()) },
        listOf(file), 0, 0, true, 1, true,
    )
    private fun show(onCommit: (String) -> Unit = {}, onBrowser: () -> Unit = {}) {
        every { model.state } returns MutableStateFlow(RepoCompareUiState(comparison = comparison))
        compose.setContent {
            MaterialTheme {
                RepoCompareScreen("o", "r", "main", "tags/v1", {}, {}, { _, _ -> }, onBrowser, onCommit, {}, model)
            }
        }
    }

    @Test fun commitsLoadMoreOnlyWhenTheirFooterIsVisibleAndOpenTheirExactSha() {
        val opened = mutableListOf<String>()
        show(onCommit = opened::add)
        compose.runOnIdle { verify(exactly = 0) { model.loadMore() } }
        compose.onNodeWithText(context.getString(R.string.compare_commits, 31)).performClick()
        compose.onNodeWithText("Commit 0").performClick()
        compose.runOnIdle {
            assertEquals(listOf("sha0"), opened)
            verify(exactly = 0) { model.loadMore() }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.runOnIdle { verify(exactly = 1) { model.loadMore() } }
    }

    @Test fun missingPatchAndFileLimitAreExplainedAndOriginalBrowserActionRemainsAvailable() {
        var opened = 0
        show(onBrowser = { opened++ })
        compose.onNodeWithText(context.getString(R.string.compare_files_limited, 1)).assertExists()
        compose.onNodeWithText("image.png").performClick()
        compose.onNodeWithText(context.getString(R.string.compare_patch_unavailable)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.git_revert_file)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.compare_open_browser)).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
