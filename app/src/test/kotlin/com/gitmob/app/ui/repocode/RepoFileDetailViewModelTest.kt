package com.gitmob.app.ui.repocode

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.core.markdown.MarkdownRenderer
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoContentSource
import com.gitmob.app.data.model.RepoLinkKind
import com.gitmob.app.data.model.RepoLinkTarget
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
import kotlinx.coroutines.flow.first
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

    private fun viewModel(renderer: MarkdownRenderer = CommonMarkRenderer()) = RepoFileDetailViewModel(
        repository, renderer, ErrorEventBus(), mockk(), updates, accounts.session, documentRepository,
    )

    @Test fun `unresolved link uses page loading and retries resolution before reading content`() = runTest {
        val source = RepoContentSource.Link(listOf("feature", "docs", "guide.md"))
        val response = CompletableDeferred<ApiResult<RepoLinkTarget>>()
        val target = RepoLinkTarget(ref, "guide.md")
        coEvery { repository.resolveLink("owner", "repo", RepoLinkKind.FILE, source.refAndPath) } coAnswers { response.await() }
        coEvery { repository.getPathContent("owner", "repo", ref, target.path) } returns ApiResult.Success(file(target.path, "# Guide"))
        val vm = viewModel()
        vm.init("owner", "repo", source)
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.target)
        coVerify(exactly = 0) { repository.getPathContent(any(), any(), any(), any()) }
        response.complete(ApiResult.Failure(ApiError.NetworkError))
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.isLoading)
        coEvery { repository.resolveLink("owner", "repo", RepoLinkKind.FILE, source.refAndPath) } returns ApiResult.Success(target)
        vm.retry()
        vm.awaitLoaded()
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(target, vm.state.value.target)
        assertEquals(RepoDocumentContext("owner", "repo", ref, target.path), vm.state.value.previewContext)
        coVerify(exactly = 2) { repository.resolveLink("owner", "repo", RepoLinkKind.FILE, source.refAndPath) }
        coVerify(exactly = 1) { repository.getPathContent("owner", "repo", ref, target.path) }
    }

    @Test fun `content retry reuses resolved target and directory fallback preserves it`() = runTest {
        val source = RepoContentSource.Link(listOf("feature", "docs", "api"))
        val target = RepoLinkTarget(ref, "api")
        coEvery { repository.resolveLink("owner", "repo", RepoLinkKind.FILE, source.refAndPath) } returns ApiResult.Success(target)
        coEvery { repository.getPathContent("owner", "repo", ref, "api") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(RepoPathContent.Directory),
        )
        val vm = viewModel()
        vm.init("owner", "repo", source)
        vm.awaitLoaded()
        assertTrue(vm.state.value.loadFailed)
        vm.retry()
        vm.awaitLoaded()
        assertTrue(vm.state.value.isDirectory)
        assertFalse(vm.state.value.isLoading)
        assertEquals(target, vm.state.value.target)
        coVerify(exactly = 1) { repository.resolveLink(any(), any(), any(), any()) }
        coVerify(exactly = 2) { repository.getPathContent("owner", "repo", ref, "api") }
    }

    @Test fun commitSnapshotCannotBeDeletedEvenForOwnerWithWritePermission() = runTest {
        val commit = "a".repeat(40)
        val content = file("Main.kt", "fun main() = Unit").copy(
            isCommitSnapshot = true, ref = commit,
            capabilities = com.gitmob.app.core.permission.RepoPermission.ADMIN.toCapabilities(),
        )
        coEvery { repository.getPathContent("owner", "repo", commit, "Main.kt") } returns ApiResult.Success(content)
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(commit, "Main.kt"))
        vm.awaitLoaded()
        assertTrue(vm.state.value.file!!.isCommitSnapshot)
        vm.deleteFile("delete") { fail("Snapshot deletion must not run") }
        coVerify(exactly = 1) { repository.getPathContent("owner", "repo", commit, "Main.kt") }
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun commitDirectoryUsesTheSameReaderAndRequestsDirectoryNavigation() = runTest {
        val commit = "a".repeat(40)
        coEvery { repository.getPathContent("owner", "repo", commit, "docs") } returns ApiResult.Success(RepoPathContent.Directory)
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(commit, "docs"))
        vm.awaitLoaded()
        assertTrue(vm.state.value.isDirectory)
        assertFalse(vm.state.value.loadFailed)
        assertNull(vm.state.value.file)
    }

    @Test fun commitDocumentPreservesVersionAndIgnoresBranchEdits() = runTest {
        val commit = "a".repeat(40)
        val path = "README.md"
        coEvery { repository.getPathContent("owner", "repo", commit, path) } returns
            ApiResult.Success(file(path, "[Docs](docs)").copy(ref = commit, headOid = commit, isCommitSnapshot = true))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(commit, path))
        vm.awaitLoaded()
        assertEquals(RepoDocumentContext("owner", "repo", commit, path), vm.state.value.previewContext)
        withContext(accounts.context) { updates.emit(RepoUpdateEvent.CodeChanged("owner", "repo", "main", "new-head", listOf(path))) }
        vm.awaitLoaded()
        coVerify(exactly = 1) { repository.getPathContent("owner", "repo", commit, path) }
    }

    private suspend fun RepoFileDetailViewModel.awaitLoaded() = state.first { !it.isLoading }

    private fun file(path: String, text: String) = RepoFileContent(
        repositoryId = "R_repo", permission = RepoPermission.READ, capabilities = RepoCapabilities.NONE,
        isArchived = false, ref = ref, headOid = "head", path = path, oid = "blob",
        byteSize = text.toByteArray().size.toLong(), isBinary = false, isTruncated = false, text = text,
    )

    @Test fun `document navigation starts in preview and preserves source path and ref`() = runTest {
        val path = "docs/翻译本页.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "[Next](../next.md)\n\n![Picture](images/图.png)"))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
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
        vm.init("owner", "repo", RepoLinkTarget(ref, "docs"))
        vm.awaitLoaded()
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
        vm.init("owner", "repo", RepoLinkTarget(ref, "docs"))
        vm.awaitLoaded()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.isDirectory)
        vm.retry()
        vm.awaitLoaded()
        assertTrue(vm.state.value.isDirectory)
        assertFalse(vm.state.value.loadFailed)
    }

    @Test fun accountSwitchCancelsPendingDirectoryLookup() = runTest {
        val response = CompletableDeferred<ApiResult<RepoPathContent>>()
        coEvery { repository.getPathContent("owner", "repo", ref, "docs") } coAnswers { response.await() }
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, "docs"))
        assertTrue(vm.state.value.isLoading)
        accounts.session.replace(emptyList(), null)
        response.complete(ApiResult.Success(RepoPathContent.Directory))
        assertFalse(vm.state.value.isDirectory)
    }

    @Test fun `normal code browsing defaults to document preview`() = runTest {
        val path = "docs/guide.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "# Guide"))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        assertTrue(vm.state.value.previewMode)
        assertTrue(vm.state.value.previewSupported)
    }

    @Test fun `HTML previews retain raw image and link attributes with document context`() = runTest {
        val path = "docs/guide.html"
        val html = "<a href=\"../next.md\">Next</a><img src=\"images/a.png\">"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, html))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        assertEquals(html, vm.state.value.previewHtml)
        assertEquals(RepoDocumentContext("owner", "repo", ref, path), vm.state.value.previewContext)
    }

    @Test fun `non-document targets remain source files even when opened from a document`() = runTest {
        val path = "src/main.kt"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "fun main() {}"))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
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
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        assertEquals(com.gitmob.app.data.model.DocumentResourceKind.IMAGE, vm.state.value.resourcePreview)
        assertTrue(vm.state.value.previewMode)
        assertTrue(vm.state.value.previewSupported)
        assertEquals(RepoDocumentContext("owner", "repo", ref, path), vm.state.value.previewContext)
    }

    @Test fun svgDefaultsToPreviewAndRetainsSourceWhenSwitchingModes() = runTest {
        val path = "docs/logo.svg"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "<svg/>"))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        assertTrue(vm.state.value.previewMode)
        assertEquals(com.gitmob.app.data.model.DocumentResourceKind.IMAGE, vm.state.value.resourcePreview)
        vm.setPreviewMode(false)
        assertFalse(vm.state.value.previewMode)
        assertEquals("<svg/>", vm.state.value.file!!.text)
        vm.retry()
        vm.awaitLoaded()
        assertFalse(vm.state.value.previewMode)
    }

    @Test fun otherBinaryFilesRemainNonPreviewable() = runTest {
        val path = "archive.zip"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns
            ApiResult.Success(file(path, "").copy(isBinary = true, text = null))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        assertEquals(com.gitmob.app.data.model.DocumentResourceKind.UNKNOWN, vm.state.value.resourcePreview)
        assertFalse(vm.state.value.previewSupported)
        assertNull(vm.state.value.previewContext)
    }

    @Test fun refreshInvalidatesImagesAndChangesPreviewRevisionEvenForIdenticalMarkdown() = runTest {
        val path = "docs/guide.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "![Image](a.png)"))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        val previous = vm.state.value
        verify(exactly = 0) { documentRepository.invalidate(any(), any()) }
        vm.load()
        vm.awaitLoaded()
        verify(exactly = 1) { documentRepository.invalidate(RepoDocumentContext("owner", "repo", ref, path), null) }
        assertEquals(previous.previewHtml, vm.state.value.previewHtml)
        assertTrue(vm.state.value.previewRevision > previous.previewRevision)
    }

    @Test fun changingReferencedImageReloadsDocumentWithoutChangingItsMarkdown() = runTest {
        val path = "docs/guide.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "![Image](images/a.png)"))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        val previous = vm.state.value.previewRevision
        withContext(accounts.context) { updates.emit(RepoUpdateEvent.CodeChanged("owner", "repo", ref, "commit", listOf("docs/images/a.png"))) }
        vm.awaitLoaded()
        coVerify(exactly = 2) { repository.getPathContent("owner", "repo", ref, path) }
        assertTrue(vm.state.value.previewRevision > previous)
    }


    @Test fun allDocumentExtensionsDefaultToPreview() = runTest {
        for (extension in listOf("md", "MARKDOWN", "html", "HTM")) {
            val path = "docs/guide.$extension"
            coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "Document"))
            val vm = viewModel()
            vm.init("owner", "repo", RepoLinkTarget(ref, path))
            vm.awaitLoaded()
            assertTrue(extension, vm.state.value.previewMode)
            assertTrue(vm.state.value.canSwitchDisplayMode)
            assertFalse(vm.state.value.wrapLines)
        }
    }

    @Test fun modeAndWrapChangesDoNotReloadAndSurviveRefreshAndFailure() = runTest {
        val path = "README.md"
        val content = file(path, "# Guide")
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(content)
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        vm.setPreviewMode(false)
        vm.setWrapLines(true)
        vm.setPreviewMode(true)
        vm.setPreviewMode(false)
        coVerify(exactly = 1) { repository.getPathContent("owner", "repo", ref, path) }
        withContext(accounts.context) { updates.emit(RepoUpdateEvent.CodeChanged("owner", "repo", ref, "next", listOf(path))) }
        vm.awaitLoaded()
        assertFalse(vm.state.value.previewMode)
        assertTrue(vm.state.value.wrapLines)
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Failure(ApiError.NetworkError)
        vm.retry()
        vm.awaitLoaded()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.previewMode)
        assertTrue(vm.state.value.wrapLines)
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(content)
        vm.retry()
        vm.awaitLoaded()
        assertFalse(vm.state.value.previewMode)
        assertTrue(vm.state.value.wrapLines)
    }

    @Test fun missingTextIsNotAnEmptyPreviewWhileEmptyAndTruncatedTextRemainAvailable() = runTest {
        for (extension in listOf("md", "html", "kt")) {
            val path = "file.$extension"
            coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "").copy(text = null))
            val missing = viewModel().apply { init("owner", "repo", RepoLinkTarget(ref, path)); awaitLoaded() }.state.value
            assertFalse(missing.sourceAvailable)
            assertFalse(missing.previewSupported)
            assertNull(missing.previewHtml)
            for (content in listOf(file(path, ""), file(path, "partial").copy(isTruncated = true))) {
                coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(content)
                val state = viewModel().apply { init("owner", "repo", RepoLinkTarget(ref, path)); awaitLoaded() }.state.value
                assertTrue(state.sourceAvailable)
                assertEquals(extension != "kt", state.previewMode)
                assertEquals(content, state.file)
            }
        }
    }

    @Test fun firstAvailableDocumentDefaultsToPreviewAfterMissingText() = runTest {
        val path = "README.md"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returnsMany listOf(
            ApiResult.Success(file(path, "").copy(text = null)),
            ApiResult.Success(file(path, "# Guide")),
        )
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        assertFalse(vm.state.value.previewSupported)
        vm.retry()
        vm.awaitLoaded()
        assertTrue(vm.state.value.previewMode)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun markdownRenderRunsOffCallerThreadAndCancelledRenderCannotReplaceNewContent() = runTest {
        val started = CompletableDeferred<Thread>()
        val release = CompletableDeferred<Unit>()
        lateinit var oldLoad: Job
        val callerThread = Thread.currentThread()
        val renderer = object : MarkdownRenderer {
            override fun renderToHtml(markdown: String): String {
                if (markdown == "old") {
                    started.complete(Thread.currentThread())
                    runBlocking { release.await() }
                }
                return "<p>$markdown</p>"
            }
        }
        coEvery { repository.getPathContent("owner", "repo", ref, "README.md") } returnsMany listOf(
            ApiResult.Success(file("README.md", "old")),
            ApiResult.Success(file("README.md", "new")),
        )
        val vm = viewModel(renderer)
        try {
            vm.init("owner", "repo", RepoLinkTarget(ref, "README.md"))
            assertNotSame(callerThread, started.await())
            assertTrue(vm.state.value.isLoading)
            val initialJobs = vm.viewModelScope.coroutineContext.job.children.toList()
            vm.retry()
            oldLoad = initialJobs.single { it.isCancelled }
            vm.awaitLoaded()
            assertEquals("<p>new</p>", vm.state.value.previewHtml)
        } finally {
            release.complete(Unit)
        }
        oldLoad.join()
        assertEquals("new", vm.state.value.file!!.text)
        assertEquals("<p>new</p>", vm.state.value.previewHtml)
    }

    @Test fun binaryImagesCannotSwitchToUnavailableSource() = runTest {
        val path = "image.png"
        coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(file(path, "").copy(isBinary = true, text = null))
        val vm = viewModel()
        vm.init("owner", "repo", RepoLinkTarget(ref, path))
        vm.awaitLoaded()
        vm.setPreviewMode(false)
        assertFalse(vm.state.value.canSwitchDisplayMode)
        assertTrue(vm.state.value.previewMode)
    }
    @Test fun binaryMediaDefaultsToNativePreviewWithoutLosingFileActions() = runTest {
        for (path in listOf("movie.mp4", "music.flac")) {
            val media = file(path, "").copy(isBinary = true, text = null, byteSize = 50L * 1024 * 1024)
            coEvery { repository.getPathContent("owner", "repo", ref, path) } returns ApiResult.Success(media)
            val vm = viewModel()
            vm.init("owner", "repo", RepoLinkTarget(ref, path))
            vm.awaitLoaded()
            assertTrue(vm.state.value.previewMode)
            assertTrue(vm.state.value.previewSupported)
            assertFalse(vm.state.value.canSwitchDisplayMode)
            assertEquals(media.capabilities, vm.state.value.file!!.capabilities)
            assertEquals(RepoDocumentContext("owner", "repo", ref, path), vm.state.value.previewContext)
        }
    }

}
