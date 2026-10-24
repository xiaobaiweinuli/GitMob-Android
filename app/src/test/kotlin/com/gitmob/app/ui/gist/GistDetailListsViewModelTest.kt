package com.gitmob.app.ui.gist

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.GistFork
import com.gitmob.app.data.model.GistForkPage
import com.gitmob.app.data.model.GistRevision
import com.gitmob.app.data.model.GistRevisionPage
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GistDetailListsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val repository = mockk<GistRepository>()
    private val errorBus = ErrorEventBus()

    @Test
    fun `修订分页防重并追加下一页`() = runTest {
        coEvery { repository.getRevisions("abc", 1) } returns ApiResult.Success(GistRevisionPage(listOf(revision("new")), 2))
        coEvery { repository.getRevisions("abc", 2) } returns ApiResult.Success(GistRevisionPage(listOf(revision("old")), null))
        val viewModel = GistRevisionsViewModel(repository, errorBus)

        viewModel.init("abc")
        advanceUntilIdle()
        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("new", "old"), viewModel.state.value.items.map { it.version })
        assertFalse(viewModel.state.value.isLoadingMore)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `复刻分页追加下一页`() = runTest {
        coEvery { repository.getForks("abc", 1) } returns ApiResult.Success(GistForkPage(listOf(GistFork("one", null, "", "")), 2))
        coEvery { repository.getForks("abc", 2) } returns ApiResult.Success(GistForkPage(listOf(GistFork("two", null, "", "")), null))
        val viewModel = GistForksViewModel(repository, errorBus)

        viewModel.init("abc")
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("one", "two"), viewModel.state.value.items.map { it.id })
        viewModel.viewModelScope.cancel()
    }

    private fun revision(version: String) = GistRevision(version, null, "", 0, 0, 0)
}
