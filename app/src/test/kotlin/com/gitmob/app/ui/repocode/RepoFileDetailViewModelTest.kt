package com.gitmob.app.ui.repocode

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.model.RepoFileContent
import com.gitmob.app.data.model.RepoPathContent
import com.gitmob.app.core.error.ApiError
import kotlinx.coroutines.CompletableDeferred
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.data.repository.RepoDocumentRepository
import com.gitmob.app.core.event.RepoUpdateEvent
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.coroutines.withContext
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class RepoFileDetailViewModelTest {
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)
    private val repository = mockk<RepoGitRepository>()
    private val documentRepository = mockk<RepoDocumentRepository>(relaxUnitFun = true)
    private val updates = RepoUpdateEventBus()
    private val ref = "feature/docs"

    private fun viewModel() = RepoFileDetailViewModel(
        repository, CommonMarkRenderer(), ErrorEventBus(), mockk(), updates, accounts.session, documentRepository,
    )

    @Test fun commitSnapshotCannotBeDeletedEvenForOwnerWithWritePermission() = runTest {
        val commit = "a".repeat(40)
        val content = file("Main.kt", "fun main() = Unit").copy(
            isCommitSnapshot = true, ref = commit,
            capabilities = com.gitmob.app.core.permission.RepoPermission.ADMIN.toCapabilities(),
        )
        coEvery { repository.getFileSnapshot("owner", "repo", commit, "Main.kt") } returns ApiResult.Success(content)
        val vm = viewModel()
        vm.init("owner", "repo", commit, "Main.kt", isCommitSnapshot = true)
        assertTrue(vm.state.value.file!!.isCommitSnapshot)
        vm.deleteFile("delete") { fail("Snapshot deletion must not run") }
        coVerify(exactly = 0) { repository.getPathContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun file(path: String, text: String) = RepoFileContent(
        repositoryId = "R_repo", permission = RepoPermission.READ, capabilities = RepoCapabilities.NONE,
        isArchived = false, ref = ref, headOid = "head", path = path, oid = "blob",
        byteSize = text.toByteArray().size.toLong(), isBinary = false, isTruncated = false, text = text,
    )

    @Test fun `document navigation starts in preview and preserves source path and ref`() = runTest {
        val path = "docs/翻译本页.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "[Next](../next.md)\n\n![Picture](images/图.png)"))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path, initialPreview = true)
        val state = vm.state.value
        assertFalse(state.isLoading)
        assertTrue(state.previewMode)
        assertTrue(state.previewSupported)
        assertEquals(RepoDocumentContext("owner", "repo", ref, path), state.previewContext)
        assertTrue(state.previewHtml!!.contains("href=\"../next.md\""))
        assertTrue(state.previewHtml.contains("<img src="))
    }

    @Test fun directoryWithoutTrailingSlashRequestsDirectoryNavigation() = runTest {
        coEvery { repository.getPathContent("owner", "repo", ref, "docs") } returns ApiResult.Success(RepoPathContent.Directory)
        val vm = viewModel()
        vm.init("owner", "repo", ref, "docs", initialPreview = true)
        assertTrue(vm.state.value.isDirectory)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loadFailed)
        assertNull(vm.state.value.file)
        coVerify(exactly = 1) { repository.getPathContent("owner", "repo", ref, "docs") }
    }

    @Test fun failedPathLookupCanRetryIntoDirectory() = runTest {
        coEvery { repository.getPathContent("owner", "repo", ref, "docs") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(RepoPathContent.Directory),
        )
        val vm = viewModel()
        vm.init("owner", "repo", ref, "docs", initialPreview = true)
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.isDirectory)
        vm.retry()
        assertTrue(vm.state.value.isDirectory)
        assertFalse(vm.state.value.loadFailed)
    }

    @Test fun accountSwitchCancelsPendingDirectoryLookup() = runTest {
        val response = CompletableDeferred<ApiResult<RepoPathContent>>()
        coEvery { repository.getPathContent("owner", "repo", ref, "docs") } coAnswers { response.await() }
        val vm = viewModel()
        vm.init("owner", "repo", ref, "docs", initialPreview = true)
        assertTrue(vm.state.value.isLoading)
        accounts.session.replace(emptyList(), null)
        response.complete(ApiResult.Success(RepoPathContent.Directory))
        assertFalse(vm.state.value.isDirectory)
    }

    @Test fun `normal code browsing keeps the source tab as its default`() = runTest {
        val path = "docs/guide.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "# Guide"))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path)
        assertFalse(vm.state.value.previewMode)
        assertTrue(vm.state.value.previewSupported)
    }

    @Test fun `HTML previews retain raw image and link attributes with document context`() = runTest {
        val path = "docs/guide.html"
        val html = "<a href=\"../next.md\">Next</a><img src=\"images/a.png\">"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, html))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path, initialPreview = true)
        assertEquals(html, vm.state.value.previewHtml)
        assertEquals(RepoDocumentContext("owner", "repo", ref, path), vm.state.value.previewContext)
    }

    @Test fun `non-document targets remain source files even when opened from a document`() = runTest {
        val path = "src/main.kt"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "fun main() {}"))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path, initialPreview = true)
        assertFalse(vm.state.value.previewSupported)
        assertNull(vm.state.value.previewHtml)
        assertNull(vm.state.value.previewContext)
        assertEquals("fun main() {}", vm.state.value.file!!.text)
    }

    @Test fun binaryImageDefaultsToPreviewWithRepositoryContext() = runTest {
        val path = "docs/images/PHOTO.PNG"
        val image = file(path, "").copy(isBinary = true, text = null, byteSize = 100)
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(image)
        val vm = viewModel()
        vm.init("owner", "repo", ref, path)
        assertTrue(vm.state.value.imagePreview)
        assertTrue(vm.state.value.previewMode)
        assertTrue(vm.state.value.previewSupported)
        assertEquals(RepoDocumentContext("owner", "repo", ref, path), vm.state.value.previewContext)
    }

    @Test fun svgDefaultsToPreviewAndRetainsSourceWhenSwitchingTabs() = runTest {
        val path = "docs/logo.svg"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "<svg/>"))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path)
        assertTrue(vm.state.value.previewMode)
        assertTrue(vm.state.value.imagePreview)
        vm.setPreviewMode(false)
        assertFalse(vm.state.value.previewMode)
        assertEquals("<svg/>", vm.state.value.file!!.text)
        vm.retry()
        assertFalse(vm.state.value.previewMode)
    }

    @Test fun otherBinaryFilesRemainNonPreviewable() = runTest {
        val path = "archive.zip"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns
            ApiResult.Success(file(path, "").copy(isBinary = true, text = null))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path)
        assertFalse(vm.state.value.imagePreview)
        assertFalse(vm.state.value.previewSupported)
        assertNull(vm.state.value.previewContext)
    }

    @Test fun refreshInvalidatesImagesAndChangesPreviewRevisionEvenForIdenticalMarkdown() = runTest {
        val path = "docs/guide.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "![Image](a.png)"))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path, initialPreview = true)
        val previous = vm.state.value
        verify(exactly = 0) { documentRepository.invalidate(any(), any()) }
        vm.load()
        verify(exactly = 1) { documentRepository.invalidate(RepoDocumentContext("owner", "repo", ref, path), null) }
        assertEquals(previous.previewHtml, vm.state.value.previewHtml)
        assertTrue(vm.state.value.previewRevision > previous.previewRevision)
    }

    @Test fun changingReferencedImageReloadsDocumentWithoutChangingItsMarkdown() = runTest {
        val path = "docs/guide.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "![Image](images/a.png)"))
        val vm = viewModel()
        vm.init("owner", "repo", ref, path, initialPreview = true)
        val previous = vm.state.value.previewRevision
        withContext(accounts.context) { updates.emit(RepoUpdateEvent.CodeChanged("owner", "repo", ref, "commit", listOf("docs/images/a.png"))) }
        coVerify(exactly = 2) { repository.getPathContent("owner", "repo", ref, path) }
        assertTrue(vm.state.value.previewRevision > previous)
    }

}
