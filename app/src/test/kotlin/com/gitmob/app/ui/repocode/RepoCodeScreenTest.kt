package com.gitmob.app.ui.repocode

import android.app.Application
import android.content.Intent
import android.content.Context
import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.ExistingRepoPullRequest
import com.gitmob.app.data.model.RepoEntryType
import com.gitmob.app.data.model.RepoTreeEntry
import com.gitmob.app.data.model.RepoCodeTree
import com.gitmob.app.data.model.RepoForkSyncInfo
import com.gitmob.app.data.model.RepoForkSyncStatus
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoCodeScreenTest {
    private val composeRule = createComposeRule()
    private val accounts = AccountSessionRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<RepoGitRepository>()
    private val pullRequests = mockk<RepoPullRequestRepository>()
    private val downloadLauncher = mockk<ExternalDownloadLauncher>(relaxed = true)
    private lateinit var viewModel: RepoCodeViewModel
    private var existingPr: Int? = null
    private var createdPr: RepoForkSyncInfo? = null
    private val comparison = RepoForkSyncInfo(
        repositoryId = "fork-id", branch = "main", currentOid = "forksha",
        upstreamRepositoryId = "upstream-id", upstreamOwner = "upstream", upstreamName = "repo", upstreamOid = "upsha",
        status = RepoForkSyncStatus.READY, aheadBy = 2, behindBy = 3,
    )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    @Test
    fun `sync dialog shows source to target and keeps merge and cancel in the footer`() {
        render()
        openSync()

        composeRule.onNodeWithText(context.getString(R.string.repo_sync_direction, "upstream", "repo", "main", "viewer", "fork"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_counts, 2, 3)).assertIsDisplayed()
        node(R.string.repo_sync_discard).assertIsDisplayed()
        node(R.string.repo_sync_pr).performScrollTo().assertIsDisplayed()
        val secondary = node(R.string.repo_sync_pr).fetchSemanticsNode().boundsInRoot
        val merge = node(R.string.repo_sync_merge).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(merge.top >= secondary.bottom)
        node(R.string.common_cancel).assertIsDisplayed().performClick()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
    }

    @Test
    fun `sync PR asks for base and head confirmation before looking up an existing PR`() {
        coEvery { pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main") } returns
            ApiResult.Success(ExistingRepoPullRequest(12, "Sync", "https://example.test/pr/12"))
        render()
        openSync()
        node(R.string.repo_sync_pr).performScrollTo().performClick()

        node(R.string.repo_sync_pr_title).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_pr_base, "viewer", "fork", "main")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_pr_head, "upstream", "repo", "main")).assertIsDisplayed()
        coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }
        node(R.string.common_continue).performClick()

        assertEquals(12, existingPr)
        assertNull(createdPr)
        node(R.string.repo_sync_pr_title).assertDoesNotExist()
        coVerify(exactly = 1) { pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main") }
    }

    @Test
    fun `discard requires the exact branch and a changed SHA resets the confirmation input`() {
        render()
        coEvery { repository.getForkSync("viewer", "fork", "main") } returnsMany listOf(
            ApiResult.Success(comparison), ApiResult.Success(comparison.copy(currentOid = "new-head")),
        )
        coEvery { repository.forceAlignFork("viewer", "fork", comparison) } returns
            ApiResult.Failure(ApiError.UserVisible(R.string.repo_sync_head_changed))
        openSync()
        node(R.string.repo_sync_discard).performScrollTo().performClick()

        node(R.string.repo_sync_discard_title).assertIsDisplayed()
        node(R.string.repo_sync_discard).assertIsNotEnabled()
        node(R.string.repo_sync_confirm_branch).performScrollTo().performTextReplacement("Main")
        node(R.string.repo_sync_discard).assertIsNotEnabled()
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
        node(R.string.repo_sync_confirm_branch).performTextReplacement("main")
        node(R.string.repo_sync_discard).assertIsEnabled().performClick()

        node(R.string.repo_sync_discard_title).assertDoesNotExist()
        node(R.string.repo_sync_discard).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_current_head, "new-head")).assertIsDisplayed()
        node(R.string.repo_sync_discard).assertIsNotEnabled()
        coVerify(exactly = 1) { repository.forceAlignFork(any(), any(), any()) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `discard confirmation remains editable and cancellable in a narrow short window`() {
        render()
        openSync()
        node(R.string.repo_sync_discard).performScrollTo().performClick()

        node(R.string.repo_sync_confirm_branch).performScrollTo().assertIsDisplayed().performTextReplacement("main")
        node(R.string.repo_sync_discard).assertIsDisplayed().assertIsEnabled()
        node(R.string.common_cancel).assertIsDisplayed().performClick()

        node(R.string.repo_sync_discard_title).assertDoesNotExist()
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
    }

    @Test
    fun `archived fork presents status without merge discard or PR entries`() {
        render(archived = true)
        openSync()

        node(R.string.repo_sync_no_permission).assertIsDisplayed()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        node(R.string.repo_sync_discard).assertDoesNotExist()
        node(R.string.repo_sync_pr).assertDoesNotExist()
        node(R.string.common_cancel).assertIsDisplayed()
    }

    @Test
    fun `unrelated histories are labeled separately from a merge conflict`() {
        render()
        coEvery { repository.getForkSync("viewer", "fork", "main") } returns ApiResult.Success(
            comparison.copy(status = RepoForkSyncStatus.NO_COMMON_ANCESTOR),
        )
        openSync()

        node(R.string.repo_sync_no_common_ancestor).assertIsDisplayed()
        node(R.string.repo_sync_conflict).assertDoesNotExist()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        node(R.string.repo_sync_discard).assertDoesNotExist()
        node(R.string.repo_sync_pr).assertDoesNotExist()
    }

    @Test
    fun `failed comparison offers retry and restores operations only after success`() {
        render()
        coEvery { repository.getForkSync("viewer", "fork", "main") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(comparison),
        )
        openSync()
        node(R.string.common_load_failed).assertIsDisplayed()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        node(R.string.repo_sync_pr).assertDoesNotExist()
        node(R.string.common_retry).performClick()

        node(R.string.repo_sync_merge).assertIsEnabled()
        coVerify(exactly = 2) { repository.getForkSync("viewer", "fork", "main") }
    }

    @Test
    fun `commit directory allows browsing but hides write and sync actions`() {
        var openedDirectory: String? = null
        var openedFile: String? = null
        render(
            ref = "a".repeat(40), snapshot = true, permission = RepoPermission.ADMIN,
            entries = listOf(
                RepoTreeEntry("docs", "docs", RepoEntryType.DIRECTORY, "tree", null, null, null),
                RepoTreeEntry("README.md", "README.md", RepoEntryType.FILE, "blob", 12, ".md", null),
            ),
            onDirectoryClick = { openedDirectory = it }, onFileClick = { openedFile = it },
        )

        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_sync_title)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_add_file)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_download_archive)).assertIsDisplayed()
        composeRule.onNodeWithText("docs").performClick()
        composeRule.onNodeWithText("README.md").performClick()
        assertEquals("docs", openedDirectory)
        assertEquals("README.md", openedFile)
    }

    @Test
    fun `writable branch keeps add and upload actions`() {
        render(permission = RepoPermission.ADMIN)
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_add_file)).performClick()
        node(R.string.repo_add_file).assertIsDisplayed()
        node(R.string.repo_upload_files).assertIsDisplayed()
        node(R.string.repo_upload_folder).assertIsDisplayed()
    }

    @Test
    fun `directory preserves entry order hides language and displays zero file size`() {
        render(entries = listOf(
            RepoTreeEntry("first.txt", "first.txt", RepoEntryType.FILE, "a", 0, ".txt", "Plain Text"),
            RepoTreeEntry("docs", "docs", RepoEntryType.DIRECTORY, "b", null, null, null),
            RepoTreeEntry("last.kt", "last.kt", RepoEntryType.FILE, "c", 1536, ".kt", "Kotlin"),
        ))
        composeRule.onNodeWithText("Plain Text").assertDoesNotExist()
        composeRule.onNodeWithText("Kotlin").assertDoesNotExist()
        composeRule.onNodeWithText(Formatter.formatShortFileSize(context, 0)).assertIsDisplayed()
        composeRule.onNodeWithText(Formatter.formatShortFileSize(context, 1536)).assertIsDisplayed()
        val first = composeRule.onNodeWithText("first.txt").fetchSemanticsNode().boundsInRoot
        val folder = composeRule.onNodeWithText("docs").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < folder.top)
    }

    @Test
    fun `subdirectory breadcrumbs and parent navigate without changing the page title`() {
        var target: String? = null
        render(path = "src/main", onDirectoryClick = { target = it })
        node(R.string.repo_code_parent).performClick()
        assertEquals("src", target)
        node(R.string.repo_code_root).performClick()
        assertEquals("", target)
        composeRule.onNodeWithText("src").performClick()
        assertEquals("src", target)
        composeRule.onNodeWithText(" · " + context.getString(R.string.repo_code)).assertIsDisplayed()
    }

    @Test
    fun `root download directly launches archive without a menu`() {
        coEvery { repository.resolveArchiveDownloadUrl("viewer", "fork", "main") } returns ApiResult.Success("https://example.test/archive.zip")
        render()
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_download_archive)).performClick()
        coVerify(exactly = 1) { repository.resolveArchiveDownloadUrl("viewer", "fork", "main") }
        verify(exactly = 1) { downloadLauncher.open("https://example.test/archive.zip") }
        node(R.string.repo_download_folder).assertDoesNotExist()
    }

    @Test
    fun `subdirectory archive option still downloads the whole repository`() {
        coEvery { repository.resolveArchiveDownloadUrl("viewer", "fork", "main") } returns ApiResult.Success("https://example.test/archive.zip")
        render(path = "src/main")
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_download)).performClick()
        node(R.string.repo_download_folder).assertIsDisplayed()
        coVerify(exactly = 0) { repository.resolveArchiveDownloadUrl(any(), any(), any()) }
        node(R.string.repo_download_archive).performClick()
        coVerify(exactly = 1) { repository.resolveArchiveDownloadUrl("viewer", "fork", "main") }
        verify(exactly = 1) { downloadLauncher.open("https://example.test/archive.zip") }
    }

    @Test
    fun `subdirectory folder option opens ZIP destination picker without downloading archive`() {
        render(path = "src/main")
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_download)).performClick()
        node(R.string.repo_download_folder).performClick()
        val intent = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals("fork-main-main.zip", intent.getStringExtra(Intent.EXTRA_TITLE))
        coVerify(exactly = 0) { repository.resolveArchiveDownloadUrl(any(), any(), any()) }
    }

    @Test
    fun `search remains available for archived read-only snapshots`() {
        var searches = 0
        render(archived = true, snapshot = true, permission = RepoPermission.READ, onSearch = { searches++ })
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_code_search))
            .assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, searches)
    }

    @Test
    fun `search waits for repository metadata and passes the fork flag`() {
        val treeGate = CompletableDeferred<Unit>()
        var searchedFork: Boolean? = null
        render(treeGate = treeGate, onSearch = { searchedFork = it })
        val search = composeRule.onNodeWithContentDescription(context.getString(R.string.repo_code_search))
        search.assertIsDisplayed().assertIsNotEnabled().performClick()
        assertNull(searchedFork)
        composeRule.runOnIdle { treeGate.complete(Unit) }
        search.assertIsEnabled().performClick()
        assertEquals(true, searchedFork)
    }

    @Test
    fun `search passes false for an ordinary repository`() {
        var searchedFork: Boolean? = null
        render(isFork = false, onSearch = { searchedFork = it })
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_code_search)).performClick()
        assertEquals(false, searchedFork)
    }

    private fun node(resource: Int) = composeRule.onNodeWithText(context.getString(resource))

    private fun openSync() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_sync_title)).performClick()
    }

    private fun render(
        archived: Boolean = false,
        ref: String = "main",
        path: String = "",
        snapshot: Boolean = false,
        permission: RepoPermission = RepoPermission.WRITE,
        entries: List<RepoTreeEntry> = emptyList(),
        isFork: Boolean = true,
        treeGate: CompletableDeferred<Unit>? = null,
        onSearch: (Boolean) -> Unit = {},
        onDirectoryClick: (String) -> Unit = {},
        onFileClick: (String) -> Unit = {},
    ) {
        coEvery { repository.getCodeTree("viewer", "fork", ref, path) } coAnswers {
            treeGate?.await()
            ApiResult.Success(
                RepoCodeTree(
                    repositoryId = "fork-id", permission = permission, capabilities = permission.toCapabilities(archived),
                    isArchived = archived, ref = ref, headOid = "forksha", path = path, entries = entries, isFork = isFork, isCommitSnapshot = snapshot,
                ),
            )
        }
        coEvery { repository.getForkSync("viewer", "fork", "main") } returns ApiResult.Success(comparison)
        viewModel = RepoCodeViewModel(repository, pullRequests, ErrorEventBus(), downloadLauncher, RepoUpdateEventBus(), accounts.session)
        composeRule.setContent {
            MaterialTheme {
                RepoCodeScreen(
                    owner = "viewer", name = "fork", ref = ref, path = path, permission = permission,
                    onBack = {},
                    onSearch = onSearch, onOwnerClick = {}, onRepositoryClick = { _, _ -> }, onAdd = {}, onDirectoryClick = onDirectoryClick, onFileClick = onFileClick,
                    onCreateSyncPullRequest = { createdPr = it }, onExistingSyncPullRequest = { existingPr = it }, viewModel = viewModel,
                )
            }
        }
    }
}
