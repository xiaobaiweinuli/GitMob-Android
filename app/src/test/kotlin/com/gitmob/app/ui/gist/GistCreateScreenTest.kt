package com.gitmob.app.ui.gist

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GistCreateScreenTest {
    private val mainDispatcherRule = MainDispatcherRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `创建页显示批准的字段且最后一个文件不可删除`() {
        val viewModel = viewModel()
        setContent(viewModel)

        composeRule.onNodeWithText(context.getString(R.string.gist_create_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.gist_create_description_optional)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.gist_create_public)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.gist_create_files)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_create_action)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).assertHasClickAction()
        composeRule.onNodeWithContentDescription(context.getString(R.string.gist_delete_file)).assertIsNotEnabled()
    }

    @Test
    fun `添加第二个文件后可删除且重复文件名禁用创建`() {
        val viewModel = viewModel()
        setContent(viewModel)
        composeRule.runOnIdle { viewModel.updateFilename(0, "same.txt") }

        composeRule.onNodeWithText(context.getString(R.string.gist_create_add_file)).performClick()
        val second = viewModel.state.value.files.last()
        composeRule.runOnIdle { viewModel.updateFilename(second.id, "same.txt") }

        composeRule.onAllNodesWithText(context.getString(R.string.gist_create_filename_duplicate))
            .assertCountEquals(2)
        composeRule.onNodeWithText(context.getString(R.string.repo_create_action)).assertIsNotEnabled()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.gist_delete_file))[1]
            .assertIsEnabled()
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, viewModel.state.value.files.size) }
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.gist_delete_file))
            .assertCountEquals(1)
    }

    @Test
    fun `创建中顶部操作替换为进度`() {
        val deferred = CompletableDeferred<ApiResult<String>>()
        val repository = mockk<GistRepository>()
        coEvery { repository.createGist(null, false, mapOf("a.txt" to "")) } coAnswers { deferred.await() }
        val viewModel = viewModel(repository)
        viewModel.updateFilename(0, "a.txt")
        setContent(viewModel)

        composeRule.onNodeWithText(context.getString(R.string.repo_create_action)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.isCreating }
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        deferred.complete(ApiResult.Success("created-id"))
        composeRule.waitUntil(timeoutMillis = 5_000) { !viewModel.state.value.isCreating }
    }

    private fun setContent(viewModel: GistCreateViewModel) {
        composeRule.setContent {
            MaterialTheme {
                GistCreateScreen(onBack = {}, onCreated = {}, viewModel = viewModel)
            }
        }
    }

    private fun viewModel(repository: GistRepository = mockk(relaxed = true)) = GistCreateViewModel(
        repository,
        ErrorEventBus(),
        GistUpdateEventBus(),
    )
}
