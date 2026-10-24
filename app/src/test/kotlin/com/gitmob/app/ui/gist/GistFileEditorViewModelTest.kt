package com.gitmob.app.ui.gist

import app.cash.turbine.test
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.model.GistFile
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GistFileEditorViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `保存新文件后通知详情刷新并返回`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery { repository.addFile("gist-id", "new.txt", "hello") } returns ApiResult.Success(Unit)
        val eventBus = GistUpdateEventBus()
        val viewModel = GistFileEditorViewModel(repository, ErrorEventBus(), eventBus)
        var saved = false
        viewModel.init("gist-id", null)
        viewModel.updatePath("new.txt")
        viewModel.updateContent("hello")

        eventBus.events.test {
            viewModel.save("gist-id") { saved = true }
            advanceUntilIdle()

            assertTrue(saved)
            assertEquals("gist-id", awaitItem())
            coVerify(exactly = 1) { repository.addFile("gist-id", "new.txt", "hello") }
            assertFalse(viewModel.state.value.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `编辑文件加载原内容并更新原文件`() = runTest {
        val repository = mockk<GistRepository>()
        val file = GistFile("old.txt", "text/plain", "Text", "https://example/raw", 5, false, "hello")
        coEvery { repository.getGistFile("gist-id", "old.txt") } returns ApiResult.Success(file)
        coEvery { repository.updateFile("gist-id", "old.txt", "new.txt", "updated") } returns ApiResult.Success(Unit)
        val eventBus = GistUpdateEventBus()
        val viewModel = GistFileEditorViewModel(repository, ErrorEventBus(), eventBus)
        var saved = false

        viewModel.init("gist-id", "old.txt")
        advanceUntilIdle()
        assertEquals("old.txt", viewModel.state.value.path)
        assertEquals("hello", viewModel.state.value.content)

        eventBus.events.test {
            viewModel.updatePath("new.txt")
            viewModel.updateContent("updated")
            viewModel.save("gist-id") { saved = true }
            advanceUntilIdle()

            assertTrue(saved)
            assertEquals("gist-id", awaitItem())
            coVerify(exactly = 1) { repository.updateFile("gist-id", "old.txt", "new.txt", "updated") }
            coVerify(exactly = 0) { repository.addFile(any(), any(), any()) }
            assertFalse(viewModel.state.value.isSaving)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
