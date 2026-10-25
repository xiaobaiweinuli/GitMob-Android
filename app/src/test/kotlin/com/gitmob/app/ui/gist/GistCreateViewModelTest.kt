package com.gitmob.app.ui.gist

import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GistCreateViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `文件名必填唯一且最后一个文件不可删除`() {
        val viewModel = viewModel()

        assertFalse(viewModel.state.value.canCreate)
        assertEquals(GistFilenameError.REQUIRED, viewModel.state.value.filenameError(viewModel.state.value.files.single()))
        viewModel.removeFile(0)
        assertEquals(1, viewModel.state.value.files.size)

        viewModel.updateFilename(0, "main.txt")
        viewModel.addFile()
        val second = viewModel.state.value.files.last()
        viewModel.updateFilename(second.id, " main.txt ")
        assertFalse(viewModel.state.value.canCreate)
        assertTrue(viewModel.state.value.files.all { viewModel.state.value.filenameError(it) == GistFilenameError.DUPLICATE })

        viewModel.removeFile(second.id)
        assertTrue(viewModel.state.value.canCreate)
    }

    @Test
    fun `创建成功保留空内容并通知列表和详情导航`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.createGist("sample", false, linkedMapOf("a.txt" to "", "b.md" to "# b"))
        } returns ApiResult.Success("created-id")
        val updateBus = GistUpdateEventBus()
        val viewModel = viewModel(repository, gistUpdateEventBus = updateBus)
        viewModel.updateDescription("sample")
        viewModel.updateFilename(0, "a.txt")
        viewModel.addFile()
        val second = viewModel.state.value.files.last()
        viewModel.updateFilename(second.id, "b.md")
        viewModel.updateContent(second.id, "# b")
        val listChanged = async(start = CoroutineStart.UNDISPATCHED) { updateBus.listEvents.first() }
        val created = async(start = CoroutineStart.UNDISPATCHED) { viewModel.createdEvents.first() }

        viewModel.create()
        advanceUntilIdle()

        listChanged.await()
        assertEquals("created-id", created.await())
        assertFalse(viewModel.state.value.isCreating)
        coVerify(exactly = 1) {
            repository.createGist("sample", false, linkedMapOf("a.txt" to "", "b.md" to "# b"))
        }
    }

    @Test
    fun `创建失败结束进度并发送统一错误`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery { repository.createGist(null, false, mapOf("a.txt" to "")) } returns
            ApiResult.Failure(ApiError.Forbidden)
        val errorBus = ErrorEventBus()
        val viewModel = viewModel(repository, errorBus)
        viewModel.updateFilename(0, "a.txt")
        val error = async(start = CoroutineStart.UNDISPATCHED) { errorBus.events.first() }

        viewModel.create()
        advanceUntilIdle()

        assertEquals(BannerEvent.Error(ApiError.Forbidden), error.await())
        assertFalse(viewModel.state.value.isCreating)
    }

    private fun viewModel(
        repository: GistRepository = mockk(relaxed = true),
        errorEventBus: ErrorEventBus = ErrorEventBus(),
        gistUpdateEventBus: GistUpdateEventBus = GistUpdateEventBus(),
    ) = GistCreateViewModel(repository, errorEventBus, gistUpdateEventBus)
}
