package com.gitmob.app.ui.repocode

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoCodeTree
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoCodeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `folder export reports progress and writes generated zip`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCodeTree("o", "r", "main", "docs") } returns ApiResult.Success(tree())
        val expected = byteArrayOf(1, 2, 3)
        coEvery { repository.createFolderZip("o", "r", "main", "docs", any()) } coAnswers {
            val progress = arg<(Int, Int) -> Unit>(4)
            progress(1, 2)
            progress(2, 2)
            ApiResult.Success(expected)
        }
        val viewModel = RepoCodeViewModel(repository, ErrorEventBus(), mockk<ExternalDownloadLauncher>(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "docs", RepoPermission.READ)
        advanceUntilIdle()

        var saved: ByteArray? = null
        viewModel.exportCurrentFolder { saved = it }
        advanceUntilIdle()

        assertArrayEquals(expected, saved)
        assertFalse(viewModel.state.value.isFolderExporting)
        assertEquals(0, viewModel.state.value.folderExportCompleted)
        assertEquals(0, viewModel.state.value.folderExportTotal)
        coVerify(exactly = 1) { repository.createFolderZip("o", "r", "main", "docs", any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `folder export can be cancelled without invoking writer`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCodeTree("o", "r", "main", "docs") } returns ApiResult.Success(tree())
        coEvery { repository.createFolderZip("o", "r", "main", "docs", any()) } coAnswers {
            suspendCancellableCoroutine { }
        }
        val viewModel = RepoCodeViewModel(repository, ErrorEventBus(), mockk<ExternalDownloadLauncher>(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "docs", RepoPermission.READ)
        advanceUntilIdle()

        var writerCalled = false
        viewModel.exportCurrentFolder { writerCalled = true }
        assertTrue(viewModel.state.value.isFolderExporting)
        viewModel.cancelFolderExport()
        advanceUntilIdle()

        assertFalse(writerCalled)
        assertFalse(viewModel.state.value.isFolderExporting)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `folder zip filename keeps repository folder and branch identity`() {
        assertEquals("repo-guides-feature-docs.zip", folderZipFileName("repo", "feature/docs", "docs/guides"))
    }

    @Test
    fun `archive download delegates signed url to external launcher`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val launcher = mockk<ExternalDownloadLauncher>(relaxed = true)
        coEvery { repository.getCodeTree("o", "r", "main", "") } returns ApiResult.Success(tree().copy(path = ""))
        coEvery { repository.resolveArchiveDownloadUrl("o", "r", "main") } returns ApiResult.Success("https://example.test/archive.zip")
        val viewModel = RepoCodeViewModel(repository, ErrorEventBus(), launcher, RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "", RepoPermission.READ)
        advanceUntilIdle()

        viewModel.downloadArchive()
        advanceUntilIdle()

        verify(exactly = 1) { launcher.open("https://example.test/archive.zip") }
        viewModel.viewModelScope.cancel()
    }

    private fun tree() = RepoCodeTree(
        repositoryId = "R1",
        permission = RepoPermission.READ,
        capabilities = RepoPermission.READ.toCapabilities(),
        isArchived = false,
        ref = "main",
        headOid = "head",
        path = "docs",
        entries = emptyList(),
    )
}
