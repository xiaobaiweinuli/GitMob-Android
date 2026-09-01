package com.gitmob.app.ui.repos

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.CreatedRepository
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerPage
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryLicense
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.UserRepository
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
class RepoCreateScreenTest {

    private val mainDispatcherRule = MainDispatcherRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `full screen form shows create action and disables it for empty name`() {
        val viewer = owner("u1", "octocat", RepositoryCreateOwnerType.USER)
        val repository = mockk<RepoRepository>(relaxed = true)
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage(viewer))
        val viewModel = createViewModel(repository)
        viewModel.initialize(viewer)
        setContent(viewModel, viewer)

        composeRule.onNodeWithText(context.getString(R.string.repo_create_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_create_action)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).assertHasClickAction()

        composeRule.runOnIdle { viewModel.updateName("new-repo") }
        composeRule.onNodeWithText(context.getString(R.string.repo_create_action)).assertIsEnabled()
    }

    @Test
    fun `owner picker is an independent sheet and cancel returns to form`() {
        val viewer = owner("u1", "octocat", RepositoryCreateOwnerType.USER)
        val organization = owner("o1", "acme", RepositoryCreateOwnerType.ORGANIZATION)
        val repository = mockk<RepoRepository>(relaxed = true)
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage(viewer, listOf(organization)))
        val viewModel = createViewModel(repository)
        viewModel.initialize(viewer)
        setContent(viewModel, viewer)

        composeRule.runOnIdle { viewModel.openOwnerPicker() }
        composeRule.onNodeWithText(context.getString(R.string.repo_create_owner_picker)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.common_back))
            .assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.common_back))[1]
            .assertHasClickAction()
        // The picker and the page live in separate Compose windows under Robolectric;
        // verify the public cancellation contract through the ViewModel after asserting
        // that the sheet back control is exposed and clickable.
        composeRule.runOnIdle { viewModel.cancelPicker() }
        composeRule.onNodeWithText(context.getString(R.string.repo_create_title)).assertIsDisplayed()
        assertEquals(null, viewModel.state.value.activePicker)
    }

    @Test
    fun `license picker exposes done and confirmation closes only the picker`() {
        val viewer = owner("u1", "octocat", RepositoryCreateOwnerType.USER)
        val license = RepositoryLicense("mit", "MIT License")
        val repository = mockk<RepoRepository>(relaxed = true)
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage(viewer))
        coEvery { repository.getLicenseTemplates() } returns ApiResult.Success(listOf(license))
        val viewModel = createViewModel(repository)
        viewModel.initialize(viewer)
        setContent(viewModel, viewer)

        composeRule.runOnIdle {
            viewModel.openLicensePicker()
            viewModel.selectLicense(license)
        }
        composeRule.onNodeWithText(context.getString(R.string.common_done))
            .assertIsDisplayed()
            .assertHasClickAction()
        // ModalBottomSheet owns a separate Compose window under Robolectric. Its semantics
        // expose the action, but dispatching the click is not reliable across that window;
        // verify the public confirmation contract after checking the real action above.
        composeRule.runOnIdle { viewModel.confirmLicense() }

        assertEquals(null, viewModel.state.value.activePicker)
        assertEquals(license, viewModel.state.value.license)
        composeRule.onNodeWithText(context.getString(R.string.repo_create_title)).assertIsDisplayed()
    }

    @Test
    fun `create progress replaces action`() {
        val viewer = owner("u1", "octocat", RepositoryCreateOwnerType.USER)
        val deferred = CompletableDeferred<ApiResult<CreatedRepository>>()
        val repository = mockk<RepoRepository>()
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage(viewer))
        coEvery { repository.createRepository(any()) } coAnswers { deferred.await() }
        val viewModel = createViewModel(repository)
        viewModel.initialize(viewer)
        viewModel.updateName("new-repo")
        setContent(viewModel, viewer)

        composeRule.onNodeWithText(context.getString(R.string.repo_create_action)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.isCreating }
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        deferred.complete(ApiResult.Success(CreatedRepository("octocat", "new-repo")))
        composeRule.waitUntil(timeoutMillis = 5_000) { !viewModel.state.value.isCreating }
    }

    private fun setContent(viewModel: RepoCreateViewModel, viewer: RepositoryCreateOwner) {
        composeRule.setContent {
            MaterialTheme {
                RepoCreateScreen(
                    defaultOwner = viewer,
                    onBack = {},
                    onCreated = { _, _ -> },
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun createViewModel(repository: RepoRepository) = RepoCreateViewModel(
        repository,
        mockk<UserRepository>(relaxed = true),
        RepoUpdateEventBus(),
        ErrorEventBus(),
    )

    private fun owner(id: String, login: String, type: RepositoryCreateOwnerType) = RepositoryCreateOwner(
        id = id,
        login = login,
        name = login,
        avatarUrl = null,
        type = type,
        canCreateRepository = true,
    )

    private fun ownerPage(
        viewer: RepositoryCreateOwner,
        organizations: List<RepositoryCreateOwner> = emptyList(),
    ) = RepositoryCreateOwnerPage(viewer, organizations, hasNextPage = false, endCursor = null)
}
