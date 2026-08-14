package com.gitmob.app.ui.gist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GistViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `首次加载原创最近修改列表`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("original"))

        val viewModel = createViewModel(repository)
        viewModel.init(null)

        assertEquals(GistCategory.ORIGINAL, viewModel.state.value.selectedCategory)
        assertEquals(GistSort.RECENTLY_UPDATED, viewModel.state.value.selectedSort)
        assertEquals(listOf("original"), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.isLoading)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `他人列表初始化刷新和分页始终传递目标login`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists("octocat", GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("first", hasNext = true, cursor = "c1"))
        coEvery {
            repository.getGistsFresh("octocat", GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED)
        } returns ApiResult.Success(page("fresh", hasNext = true, cursor = "c2"))
        coEvery {
            repository.getGists("octocat", GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c2")
        } returns ApiResult.Success(page("next"))
        val viewModel = createViewModel(repository)

        viewModel.init("octocat")
        viewModel.refresh()
        viewModel.loadMore()

        assertEquals(listOf("fresh", "next"), viewModel.state.value.items.map { it.id })
        coVerify(exactly = 1) {
            repository.getGists("octocat", GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        }
        coVerify(exactly = 1) {
            repository.getGistsFresh("octocat", GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED)
        }
        coVerify(exactly = 1) {
            repository.getGists("octocat", GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c2")
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `原创和复刻切换保留各自列表`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("original"))
        coEvery {
            repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("forked"))
        val savedState = SavedStateHandle()
        val viewModel = createViewModel(repository, savedState)
        viewModel.init(null)

        viewModel.selectCategory(GistCategory.FORKED)
        assertEquals(listOf("forked"), viewModel.state.value.items.map { it.id })

        viewModel.selectCategory(GistCategory.ORIGINAL)
        assertEquals(listOf("original"), viewModel.state.value.items.map { it.id })
        assertEquals(GistCategory.ORIGINAL.name, savedState.get<String>("gist.selectedCategory"))
        coVerify(exactly = 1) {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `切换排序重置当前类别并从第一页加载`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("old", hasNext = true, cursor = "old-cursor"))
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.OLDEST_CREATED, null)
        } returns ApiResult.Success(page("new"))
        val savedState = SavedStateHandle()
        val viewModel = createViewModel(repository, savedState)
        viewModel.init(null)

        viewModel.selectSort(GistSort.OLDEST_CREATED)

        assertEquals(listOf("new"), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.hasNextPage)
        assertEquals(GistSort.OLDEST_CREATED.name, savedState.get<String>("gist.selectedSort"))
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `刷新失败保留已有列表并结束刷新动画`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("existing"))
        coEvery {
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED)
        } returns ApiResult.Failure(ApiError.NetworkError)
        val viewModel = createViewModel(repository)
        viewModel.init(null)

        viewModel.refresh()

        assertEquals(listOf("existing"), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.isRefreshing)
        assertFalse(viewModel.state.value.loadFailed)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `连续刷新触发不会并发fresh请求`() = runTest {
        val repository = mockk<GistRepository>()
        val gate = CompletableDeferred<Unit>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("existing"))
        coEvery {
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED)
        } coAnswers {
            gate.await()
            ApiResult.Success(page("fresh"))
        }
        val viewModel = createViewModel(repository)
        viewModel.init(null)

        viewModel.refresh()
        viewModel.refresh()

        coVerify(exactly = 1) {
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED)
        }
        gate.complete(Unit)
        assertFalse(viewModel.state.value.isRefreshing)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `分页返回零项但仍有下一页时不会在ViewModel内自动循环`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("existing", hasNext = true, cursor = "c1"))
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c1")
        } returns ApiResult.Success(GistPage(emptyList(), hasNextPage = true, nextCursor = "c2"))
        val viewModel = createViewModel(repository)
        viewModel.init(null)

        viewModel.loadMore()

        assertEquals(listOf("existing"), viewModel.state.value.items.map { it.id })
        assertTrue(viewModel.state.value.hasNextPage)
        assertEquals("c2", viewModel.state.value.current.nextCursor)
        coVerify(exactly = 1) {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c1")
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `重复分页触发不会并发请求`() = runTest {
        val repository = mockk<GistRepository>()
        val gate = CompletableDeferred<Unit>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("first", hasNext = true, cursor = "c1"))
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c1")
        } coAnswers {
            gate.await()
            ApiResult.Success(page("second"))
        }
        val viewModel = createViewModel(repository)
        viewModel.init(null)

        viewModel.loadMore()
        viewModel.loadMore()
        coVerify(exactly = 1) {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c1")
        }

        gate.complete(Unit)
        assertFalse(viewModel.state.value.isLoadingMore)
        viewModel.viewModelScope.cancel()
    }

    private fun createViewModel(
        repository: GistRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = GistViewModel(repository, ErrorEventBus(), savedStateHandle)

    private fun page(
        id: String,
        hasNext: Boolean = false,
        cursor: String? = null,
    ) = GistPage(
        items = listOf(gist(id)),
        hasNextPage = hasNext,
        nextCursor = cursor,
    )

    private fun gist(id: String) = GistListItem(
        id = id,
        apiName = id,
        ownerLogin = "viewer",
        description = null,
        url = "https://gist.github.com/viewer/$id",
        isPublic = true,
        isFork = false,
        isOwnedByViewer = true,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-02-01T00:00:00Z",
        stargazerCount = 0,
        commentCount = 0,
        previewFile = null,
        fileCount = 0,
        isFileCountCapped = false,
    )
}
