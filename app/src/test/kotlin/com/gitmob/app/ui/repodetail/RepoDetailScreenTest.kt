package com.gitmob.app.ui.repodetail

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoTransferPendingStore
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepoReadme
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
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoDetailScreenTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<RepoDetailRepository>(relaxed = true)
    private val owners = mockk<RepoRepository>(relaxed = true)
    private val stars = mockk<StarRepository>()
    private lateinit var viewModel: RepoDetailViewModel
    private var openedHomepage: String? = null
    private var addressChanges = 0
    private val target = RepositoryCreateOwner("O1", "target", "Target", null, RepositoryCreateOwnerType.ORGANIZATION, true)

    @Before
    fun setUp() {
        coEvery { stars.getLists() } returns ApiResult.Success(emptyList())
        coEvery { stars.getListsContaining("R1") } returns ApiResult.Success(emptySet())
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fork admin menu hides visibility keeps archive and has no duplicate share`() {
        render(detail().copy(isFork = true))
        openMenu()

        node(R.string.repo_manage_information).assertIsDisplayed()
        node(R.string.repo_manage_archive).assertIsDisplayed()
        node(R.string.repo_manage_disable_issues).assertIsDisplayed()
        node(R.string.repo_manage_disable_discussions).assertIsDisplayed()
        node(R.string.repo_transfer_title).assertIsDisplayed()
        node(R.string.repo_manage_delete).assertIsDisplayed()
        node(R.string.repo_manage_visibility).assertDoesNotExist()
        node(R.string.repo_manage_visibility_unavailable).assertDoesNotExist()
        node(R.string.conversation_share).assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.conversation_share)).assertCountEquals(1)
    }

    @Test
    fun `archived fork retains unarchive and delete only`() {
        render(detail().copy(isFork = true, isArchived = true, capabilities = RepoPermission.ADMIN.toCapabilities(true)))
        openMenu()

        node(R.string.repo_manage_unarchive).assertIsDisplayed()
        node(R.string.repo_manage_delete).assertIsDisplayed()
        node(R.string.repo_manage_archive).assertDoesNotExist()
        node(R.string.repo_manage_information).assertDoesNotExist()
        node(R.string.repo_transfer_title).assertDoesNotExist()
        node(R.string.repo_manage_visibility).assertDoesNotExist()
    }

    @Test
    fun `read only repository keeps share and hides the empty management menu`() {
        render(detail().copy(permission = RepoPermission.READ, capabilities = RepoPermission.READ.toCapabilities()))

        composeRule.onNodeWithContentDescription(context.getString(R.string.conversation_share)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_manage_more)).assertDoesNotExist()
    }

    @Test
    fun `website is visible and invokes the external navigation callback`() {
        val url = "https://example.test/project?from=gitmob"
        render(detail().copy(homepageUrl = url))

        composeRule.onNodeWithText(url).assertIsDisplayed().performClick()
        assertEquals(url, openedHomepage)
    }

    @Test
    fun `action strip preserves order and joins star and lists without a gap`() {
        render()
        val actionNodes = listOf(
            composeRule.onNodeWithText(context.getString(R.string.repo_stargazers_count, 42)),
            composeRule.onNodeWithText(context.getString(R.string.repo_forks_count, 7)),
            node(R.string.repo_starred),
            node(R.string.repo_star_lists),
            node(R.string.repo_fork),
            composeRule.onNodeWithContentDescription(context.getString(R.string.repo_manage_notifications)),
        ).map { it.fetchSemanticsNode() }

        assertTrue(actionNodes.zipWithNext().all { (first, second) -> first.positionInRoot.x < second.positionInRoot.x })
        val star = actionNodes[2]
        val lists = actionNodes[3]
        val gap = lists.positionInRoot.x - star.positionInRoot.x - star.size.width
        assertTrue("Joined segments must share a divider, actual gap: $gap", gap in 0f..3f)
        assertEquals(star.size.height, lists.size.height)
        coVerify(exactly = 1) { stars.getLists() }
        node(R.string.repo_fork).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_manage_notifications))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `own repository hides fork while keeping the notifications button reachable`() {
        render(detail().copy(ownerIsViewer = true))

        node(R.string.repo_fork).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_manage_notifications))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_open_homepage)).assertDoesNotExist()
    }

    @Test
    fun `list selector reuses automatic membership and saves the four character multiple summary`() {
        val lists = listOf(
            UserListSummary("L1", "Android libraries", "android", null, false, 2),
            UserListSummary("L2", "Tools", "tools", null, false, 3),
        )
        coEvery { stars.getLists() } returns ApiResult.Success(lists)
        coEvery { stars.getListsContaining("R1") } returns ApiResult.Success(setOf("L1"))
        coEvery { stars.updateListsForItem("R1", setOf("L1", "L2")) } returns ApiResult.Success(Unit)
        render()
        coVerify(exactly = 1) { stars.getListsContaining("R1") }
        composeRule.onNodeWithText("Andr").performScrollTo().assertIsDisplayed().performClick()

        node(R.string.stars_create_new_list).assertIsDisplayed()
        composeRule.onNodeWithText("Tools").performClick()
        node(R.string.common_done).performClick()
        composeRule.onNodeWithText(context.getString(R.string.repo_star_lists_summary, "Andr", 1))
            .performScrollTo().assertIsDisplayed()
        coVerify(exactly = 1) { stars.getListsContaining("R1") }
        coVerify(exactly = 1) { stars.updateListsForItem("R1", setOf("L1", "L2")) }
    }

    @Test
    fun `cancelling a selection keeps the saved summary and discards the draft`() {
        coEvery { stars.getLists() } returns ApiResult.Success(listOf(
            UserListSummary("L1", "Android libraries", "android", null, false, 2),
            UserListSummary("L2", "Tools", "tools", null, false, 3),
        ))
        coEvery { stars.getListsContaining("R1") } returns ApiResult.Success(setOf("L1"))
        render()
        composeRule.onNodeWithText("Andr").performScrollTo().performClick()
        composeRule.onNodeWithText("Tools").performClick()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }

        composeRule.onNodeWithText("Andr").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNode(isToggleable() and hasAnySibling(hasText("Tools")), useUnmergedTree = true).assertIsOff()
        coVerify(exactly = 1) { stars.getListsContaining("R1") }
        coVerify(exactly = 0) { stars.updateListsForItem(any(), any()) }
    }

    @Test
    fun `background loading keeps actions enabled but the selector cannot save until loaded`() {
        val pending = CompletableDeferred<ApiResult<Set<String>>>()
        coEvery { stars.getLists() } returns ApiResult.Success(listOf(
            UserListSummary("L1", "Android libraries", "android", null, false, 2),
        ))
        coEvery { stars.getListsContaining("R1") } coAnswers { pending.await() }
        render()

        node(R.string.repo_starred).assertIsEnabled()
        node(R.string.repo_fork).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_manage_more)).assertIsEnabled()
        node(R.string.repo_star_lists).performScrollTo().assertIsEnabled().performClick()
        node(R.string.common_done).assertIsNotEnabled()
        coVerify(exactly = 0) { stars.updateListsForItem(any(), any()) }
        composeRule.runOnIdle { pending.complete(ApiResult.Success(setOf("L1"))) }

        node(R.string.common_done).assertIsEnabled()
        coVerify(exactly = 1) { stars.getLists() }
        coVerify(exactly = 1) { stars.getListsContaining("R1") }
    }

    @Test
    fun `transfer continues to confirmation and only submit sends the request`() {
        coEvery { repository.getTransferTarget("target", "repo") } returns ApiResult.Success(RepositoryTransferTarget(target, false))
        coEvery { repository.transferRepository("octo", "repo", "target", "repo") } returns ApiResult.Success(Unit)
        justRun { stars.invalidateAllCaches() }
        render()
        openMenu()
        node(R.string.repo_transfer_title).performClick()

        node(R.string.repo_transfer_continue).assertIsNotEnabled()
        composeRule.onNodeWithText("@target").performClick().assertIsSelected()
        node(R.string.repo_transfer_continue).assertIsEnabled().performClick()
        node(R.string.repo_transfer_verified).assertIsDisplayed()
        node(R.string.repo_transfer_submit).assertIsDisplayed()
        coVerify(exactly = 0) { repository.transferRepository(any(), any(), any(), any()) }

        node(R.string.common_cancel).performClick()
        assertFalse(viewModel.state.value.showTransferConfirmation)
        node(R.string.repo_transfer_continue).performClick()
        node(R.string.repo_transfer_submit).performClick()
        node(R.string.repo_transfer_accepted).assertIsDisplayed()
        assertTrue(viewModel.state.value.transferPending)
        assertEquals(0, addressChanges)
        coVerify(exactly = 1) { repository.transferRepository("octo", "repo", "target", "repo") }
    }

    @Test
    fun `transfer keyboard selection reaches other account and edited name is checked`() {
        coEvery { repository.getTransferTarget("another", "renamed") } returns ApiResult.Success(
            RepositoryTransferTarget(target.copy(login = "another"), true),
        )
        render()
        openMenu()
        node(R.string.repo_transfer_title).performClick()
        composeRule.runOnIdle {
            // 弹窗是独立窗口，需在该窗口退出触摸模式。
            assertTrue(ShadowDialog.getLatestDialog().window!!.decorView.requestFocusFromTouch())
        }
        val account = composeRule.onNodeWithText("@target")
        account.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        account.assertIsFocused()
        account.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }

        node(R.string.repo_transfer_other).assertIsSelected().assertIsFocused()
        node(R.string.repo_transfer_login).performScrollTo().performTextReplacement("another")
        node(R.string.repo_create_name).performScrollTo().performTextReplacement("renamed")
        node(R.string.repo_transfer_continue).performClick()
        assertFalse(viewModel.state.value.showTransferConfirmation)
        node(R.string.repo_transfer_name_conflict).performScrollTo().assertIsDisplayed()
        coVerify(exactly = 1) { repository.getTransferTarget("another", "renamed") }
        coVerify(exactly = 0) { repository.transferRepository(any(), any(), any(), any()) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `delete name shortcut only fills the confirmation and cancel keeps the repository`() {
        render()
        openMenu()
        node(R.string.repo_manage_delete).performScrollTo().performClick()

        node(R.string.common_delete).assertIsNotEnabled()
        node(R.string.repo_manage_confirm_name).performScrollTo().performTextReplacement("Repo")
        node(R.string.common_delete).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.repo_manage_fill_name, "repo"))
            .performScrollTo().performClick()
        node(R.string.common_delete).assertIsDisplayed().assertIsEnabled()
        assertEquals("repo", viewModel.state.value.confirmationName)
        coVerify(exactly = 0) { repository.deleteRepository(any(), any()) }

        node(R.string.common_cancel).assertIsDisplayed().performClick()
        assertFalse(viewModel.state.value.deleted)
        coVerify(exactly = 0) { repository.deleteRepository(any(), any()) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `transfer other account and repository name remain editable in a narrow short window`() {
        render()
        openMenu()
        node(R.string.repo_transfer_title).performScrollTo().performClick()

        node(R.string.repo_transfer_other).performScrollTo().performClick()
        node(R.string.repo_transfer_login).performScrollTo().assertIsDisplayed().performTextReplacement("another")
        node(R.string.repo_create_name).performScrollTo().assertIsDisplayed().performTextReplacement("renamed")
        node(R.string.repo_transfer_continue).assertIsDisplayed().assertIsEnabled()
        node(R.string.common_cancel).assertIsDisplayed().performClick()

        assertEquals("another", viewModel.state.value.transferOtherLogin)
        assertEquals("renamed", viewModel.state.value.editName)
        coVerify(exactly = 0) { repository.transferRepository(any(), any(), any(), any()) }
    }

    private fun node(resource: Int) = composeRule.onNodeWithText(context.getString(resource))

    private fun openMenu() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_manage_more)).performClick()
    }

    private fun render(detail: RepoDetail = detail()) {
        coEvery { repository.getRepoDetail("octo", "repo") } returns ApiResult.Success(detail)
        coEvery { repository.getReadme("octo", "repo", "main") } returns ApiResult.Success(RepoReadme(null, false))
        coEvery { owners.getRepositoryCreateOwners(null) } returns ApiResult.Success(
            RepositoryCreateOwnerPage(
                RepositoryCreateOwner("U1", "octo", null, null, RepositoryCreateOwnerType.USER, true),
                listOf(target), false, null,
            ),
        )
        viewModel = RepoDetailViewModel(repository, owners, stars, mockk(), RepoUpdateEventBus(), ErrorEventBus(), mockk(), RepoTransferPendingStore())
        composeRule.setContent {
            MaterialTheme {
                RepoDetailScreen(
                    owner = "octo", name = "repo", onBack = {}, onOwnerClick = {}, onForkSourceClick = { _, _ -> },
                    onHomepageClick = { openedHomepage = it }, onNavigateBranches = { _, _, _ -> }, onNavigateCode = {},
                    onNavigateCommits = {}, onNavigateWatchers = {}, onNavigateStargazers = {}, onNavigateForks = {},
                    onNavigateIssues = { _, _ -> }, onNavigatePullRequests = {}, onNavigateDiscussions = {},
                    onNavigateActions = { _, _ -> }, onNavigateReleases = {}, onNavigateContributors = {},
                    onNavigateLicense = {}, onNavigatePlaceholder = {}, onRepositoryAddressChanged = { _, _ -> addressChanges++ },
                    onRepositoryDeleted = {}, viewModel = viewModel,
                )
            }
        }
    }

    private fun detail() = RepoDetail(
        id = "R1", name = "repo", ownerLogin = "octo", ownerAvatarUrl = null,
        description = null, homepageUrl = null, isPrivate = false, isArchived = false, isTemplate = false,
        isFork = false, forkedFromOwner = null, forkedFromName = null,
        stargazerCount = 42, viewerHasStarred = true, forkCount = 7, openIssueCount = 1, openPrCount = 1, watcherCount = 2,
        viewerSubscription = "UNSUBSCRIBED", licenseName = null, licenseSpdxId = null, branchCount = 1,
        defaultBranchName = "main", releaseCount = 0, latestReleaseName = null, latestReleaseTag = null,
        languageName = null, languageColor = null, topics = emptyList(),
        capabilities = RepoPermission.ADMIN.toCapabilities(), permission = RepoPermission.ADMIN,
        hasIssuesEnabled = true, hasDiscussionsEnabled = true, viewerCanSubscribe = true, forkingAllowed = true,
    )
}
