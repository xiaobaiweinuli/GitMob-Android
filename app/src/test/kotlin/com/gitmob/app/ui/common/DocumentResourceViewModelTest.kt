package com.gitmob.app.ui.common

import com.gitmob.app.R
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.markdown.baseUrl
import com.gitmob.app.data.model.DocumentResource
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.MAX_DOCUMENT_RESOURCE_BYTES
import com.gitmob.app.data.repository.RepoDocumentRepository
import com.gitmob.app.data.repository.RepoDocumentResource
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class DocumentResourceViewModelTest {
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)
    private val repository = mockk<RepoDocumentRepository>(relaxUnitFun = true)
    private val git = mockk<RepoGitRepository>()
    private val playback = mockk<com.gitmob.app.core.media.MediaPlayback>()
    private val downloads = mockk<ExternalDownloadLauncher>(relaxed = true)
    private val document = RepoDocumentContext("owner", "repo", "feature/images", "docs/README.md")
    private val image = DocumentResource("https://images.example.test/demo.png", "https://example.test/releases")
    private val bytes = RepoDocumentResource(byteArrayOf(1, 2, 3), "image/png")
    private fun viewModel() = DocumentResourceViewModel(repository, git, downloads, ErrorEventBus(), accounts.session, playback)

    @Test fun loadsImageSourceAndDeduplicatesSameRequest() = runTest {
        coEvery { repository.loadImage(image.source, document) } returns ApiResult.Success(bytes)
        val vm = viewModel()
        vm.show(image, document)
        vm.show(image, document)
        assertSame(bytes, vm.state.value.resource)
        assertFalse(vm.state.value.isLoading)
        coVerify(exactly = 1) { repository.loadImage(image.source, document) }
    }

    @Test fun failureCanBeRetriedAndClearsPreviousError() = runTest {
        coEvery { repository.loadImage(image.source, null) } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(bytes))
        val vm = viewModel()
        vm.show(image, null)
        assertEquals(ApiError.NetworkError, vm.state.value.error)
        vm.retry()
        assertSame(bytes, vm.state.value.resource)
        assertNull(vm.state.value.error)
    }

    @Test fun knownOversizedFileIsRejectedWithoutFetching() = runTest {
        val vm = viewModel()
        vm.show(image, document, MAX_DOCUMENT_RESOURCE_BYTES.toLong() + 1)
        assertEquals(ApiError.UserVisible(R.string.image_preview_too_large), vm.state.value.error)
        coVerify(exactly = 0) { repository.loadImage(any(), any()) }
    }

    @Test fun decodeFailureReleasesBytesAndSupportsReload() = runTest {
        coEvery { repository.loadImage(any(), any()) } returns ApiResult.Success(bytes)
        val vm = viewModel()
        vm.show(image, null)
        vm.decodeFailed()
        assertNull(vm.state.value.resource)
        assertEquals(ApiError.UserVisible(R.string.image_preview_failed), vm.state.value.error)
        vm.retry()
        assertSame(bytes, vm.state.value.resource)
    }

    @Test fun closingCancelsRequestAndClearsImageState() = runTest {
        var cancelled = false
        coEvery { repository.loadImage(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val vm = viewModel()
        vm.show(image, document)
        assertTrue(vm.state.value.isLoading)
        vm.clear()
        assertTrue(cancelled)
        assertEquals(DocumentResourceState(), vm.state.value)
    }

    @Test fun switchingAccountCancelsImageRequest() = runTest {
        var cancelled = false
        coEvery { repository.loadImage(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val vm = viewModel()
        vm.show(image, document)
        accounts.session.replace(emptyList(), null)
        assertTrue(cancelled)
        assertNull(vm.state.value.resource)
        assertNull(vm.state.value.error)
    }

    @Test fun replacingImageCancelsOldLoadAndDisplaysNewResult() = runTest {
        var cancelled = false
        coEvery { repository.loadImage(image.source, null) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val next = DocumentResource("https://images.example.test/next.svg")
        coEvery { repository.loadImage(next.source, null) } returns ApiResult.Success(bytes)
        val vm = viewModel()
        vm.show(image, null)
        vm.show(next, null)
        assertTrue(cancelled)
        assertSame(bytes, vm.state.value.resource)
    }

    @Test fun repositoryDownloadResolvesActualImagePathAndRef() = runTest {
        val source = document.copy(filePath = "docs/images/demo.png").baseUrl().toString()
        coEvery { repository.loadImage(source, document) } returns ApiResult.Success(bytes)
        coEvery { git.resolveFileDownloadUrl("owner", "repo", "feature/images", "docs/images/demo.png") } returns
            ApiResult.Success("https://example.test/signed.png")
        val vm = viewModel()
        vm.show(DocumentResource(source, "https://example.test/releases"), document)
        vm.download()
        verify { downloads.open("https://example.test/signed.png") }
        assertFalse(vm.state.value.isDownloading)
    }

    @Test fun publicImageDownloadUsesSourceAndNotOriginalLink() = runTest {
        coEvery { repository.loadImage(image.source, document) } returns ApiResult.Success(bytes)
        val vm = viewModel()
        vm.show(image, document)
        vm.download()
        verify { downloads.open(image.source) }
        coVerify(exactly = 0) { git.resolveFileDownloadUrl(any(), any(), any(), any()) }
    }

    @Test fun retryPreservesPendingDownloadAndDeduplicatesIt() = runTest {
        val source = document.copy(filePath = "docs/image.png").baseUrl().toString()
        val download = CompletableDeferred<ApiResult<String?>>()
        val imageReload = CompletableDeferred<ApiResult<RepoDocumentResource>>()
        coEvery { repository.loadImage(source, document) } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { git.resolveFileDownloadUrl("owner", "repo", "feature/images", "docs/image.png") } coAnswers {
            download.await()
        }
        val vm = viewModel()
        vm.show(DocumentResource(source), document)
        vm.download()
        assertTrue(vm.state.value.isDownloading)

        coEvery { repository.loadImage(source, document) } coAnswers { imageReload.await() }
        vm.retry()
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.isDownloading)
        imageReload.complete(ApiResult.Success(bytes))
        assertSame(bytes, vm.state.value.resource)
        assertTrue(vm.state.value.isDownloading)
        vm.download()
        coVerify(exactly = 1) { git.resolveFileDownloadUrl("owner", "repo", "feature/images", "docs/image.png") }

        download.complete(ApiResult.Success("https://example.test/signed.png"))
        assertFalse(vm.state.value.isDownloading)
        verify(exactly = 1) { downloads.open("https://example.test/signed.png") }
        vm.clear()
    }

    @Test fun closingCancelsPendingDownloadWithoutLaunchingExternalApp() = runTest {
        val source = document.copy(filePath = "docs/image.png").baseUrl().toString()
        coEvery { repository.loadImage(source, document) } returns ApiResult.Success(bytes)
        var cancelled = false
        coEvery { git.resolveFileDownloadUrl(any(), any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val vm = viewModel()
        vm.show(DocumentResource(source), document)
        vm.download()
        assertTrue(vm.state.value.isDownloading)
        vm.clear()
        assertTrue(cancelled)
        verify(exactly = 0) { downloads.open(any()) }
    }

    @Test fun retryAfterDecodeFailureInvalidatesTheCanonicalRepositoryPath() = runTest {
        val raw = "https://raw.githubusercontent.com/owner/repo/feature/images/docs/photo.png"
        coEvery { repository.loadImage(raw, document) } returns ApiResult.Success(bytes)
        val vm = viewModel()
        vm.show(DocumentResource(raw), document)
        vm.decodeFailed()
        vm.retry()
        io.mockk.verify(exactly = 1) { repository.invalidate(document, "docs/photo.png") }
        coVerify(exactly = 2) { repository.loadImage(raw, document) }
        assertSame(bytes, vm.state.value.resource)
    }

    @Test fun largeVideoUsesStreamingAndClosingReleasesPlayback() = runTest {
        val video = DocumentResource("https://example.test/movie.mp4")
        val source = com.gitmob.app.data.model.MediaSource(video.source, video.kind)
        val session = mockk<com.gitmob.app.core.media.MediaPlayback.Session>(relaxed = true)
        coEvery { repository.resolveMedia(video, document) } returns ApiResult.Success(source)
        coEvery { playback.create(source, accounts.session.epoch) } returns session
        val vm = viewModel()
        vm.show(video, document, 50L * 1024 * 1024)
        assertSame(session, vm.state.value.playback)
        assertNull(vm.state.value.resource)
        assertFalse(vm.state.value.isLoading)
        coVerify(exactly = 0) { repository.loadImage(any(), any()) }
        vm.clear()
        verify(exactly = 1) { session.close() }
        assertNull(vm.state.value.playback)
    }

    @Test fun playbackInitializationFailureCanBeRetried() = runTest {
        val video = DocumentResource("https://example.test/movie.mp4")
        val source = com.gitmob.app.data.model.MediaSource(video.source, video.kind)
        val session = mockk<com.gitmob.app.core.media.MediaPlayback.Session>(relaxed = true)
        coEvery { repository.resolveMedia(video, null) } returns ApiResult.Success(source)
        coEvery { playback.create(source, any()) } throws IllegalStateException("Unsupported media source")
        val vm = viewModel()
        vm.show(video, null)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.playback)

        coEvery { playback.create(source, any()) } returns session
        vm.retry()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertSame(session, vm.state.value.playback)
        vm.clear()
        verify(exactly = 1) { session.close() }
    }

    @Test fun retryResolvesMediaAgainAndDisposesThePreviousSession() = runTest {
        val video = DocumentResource("https://example.test/movie.mp4")
        val source = com.gitmob.app.data.model.MediaSource(video.source, video.kind)
        val first = mockk<com.gitmob.app.core.media.MediaPlayback.Session>(relaxed = true)
        val second = mockk<com.gitmob.app.core.media.MediaPlayback.Session>(relaxed = true)
        coEvery { repository.resolveMedia(video, null) } returns ApiResult.Success(source)
        coEvery { playback.create(source, any()) } returnsMany listOf(first, second)
        val vm = viewModel()
        vm.show(video, null)
        vm.retry()
        verify(exactly = 1) { first.close() }
        coVerify(exactly = 2) { repository.resolveMedia(video, null) }
        assertSame(second, vm.state.value.playback)
        vm.clear()
    }

    @Test fun opaqueImageUsesDetectedImageTypeAndUnsupportedAttachmentsFallBack() = runTest {
        val attachment = DocumentResource("https://github.com/user-attachments/assets/id")
        coEvery { repository.resolveMedia(attachment, null) } returns ApiResult.Success(
            com.gitmob.app.data.model.MediaSource(attachment.source, com.gitmob.app.data.model.DocumentResourceKind.IMAGE, "image/png"))
        coEvery { repository.loadImage(attachment.source, null) } returns ApiResult.Success(bytes)
        val vm = viewModel()
        vm.show(attachment, null)
        assertSame(bytes, vm.state.value.resource)
        vm.clear()
        coEvery { repository.resolveMedia(attachment, null) } returns ApiResult.Success(
            com.gitmob.app.data.model.MediaSource(attachment.source, com.gitmob.app.data.model.DocumentResourceKind.UNKNOWN, "application/pdf"))
        vm.show(attachment, null)
        assertTrue(vm.state.value.unsupported)
        assertNull(vm.state.value.error)
        coVerify(exactly = 0) { playback.create(any(), any()) }
    }

}
