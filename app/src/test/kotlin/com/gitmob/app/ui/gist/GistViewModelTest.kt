package com.gitmob.app.ui.gist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistVisibility
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GistViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `首次加载全部最近修改列表`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ALL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("all"))

        val viewModel = createViewModel(repository, SavedStateHandle())
        viewModel.init(null)

        assertEquals(GistCategory.ALL, viewModel.state.value.selectedCategory)
        assertEquals(GistSort.RECENTLY_UPDATED, viewModel.state.value.selectedSort)
        assertEquals(GistVisibility.ALL, viewModel.state.value.selectedVisibility)
        assertEquals(listOf("all"), viewModel.state.value.items.map { it.id })
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
    fun `全部原创和复刻切换保留各自列表`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ALL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("all"))
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("original"))
        coEvery {
            repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("forked"))
        val savedState = SavedStateHandle()
        val viewModel = createViewModel(repository, savedState)
        viewModel.init(null)

        viewModel.selectCategory(GistCategory.ORIGINAL)
        assertEquals(listOf("original"), viewModel.state.value.items.map { it.id })

        viewModel.selectCategory(GistCategory.FORKED)
        assertEquals(listOf("forked"), viewModel.state.value.items.map { it.id })

        viewModel.selectCategory(GistCategory.ALL)
        assertEquals(listOf("all"), viewModel.state.value.items.map { it.id })
        assertEquals(GistCategory.ALL.name, savedState.get<String>("gist.selectedCategory"))
        coVerify(exactly = 1) {
            repository.getGists(null, GistCategory.ALL, GistSort.RECENTLY_UPDATED, null)
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
            repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null)
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
        val savedState = originalSavedState()
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
        } returns ApiResult.Success(page("existing", hasNext = true, cursor = "c1"))
        coEvery {
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED)
        } returns ApiResult.Failure(ApiError.NetworkError)
        val viewModel = createViewModel(repository)
        viewModel.init(null)

        viewModel.refresh()

        assertEquals(listOf("existing"), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.isRefreshing)
        assertFalse(viewModel.state.value.loadFailed)
        assertTrue(viewModel.state.value.hasNextPage)
        assertEquals("c1", viewModel.state.value.current.nextCursor)
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

    @Test
    fun `恢复类别排序与可见性后首次请求使用恢复条件`() = runTest {
        val savedState = SavedStateHandle(mapOf(
            "gist.selectedCategory" to "FORKED",
            "gist.selectedSort" to "OLDEST_UPDATED",
            "gist.selectedVisibility" to "PRIVATE",
        ))
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists("octocat", GistCategory.FORKED, GistSort.OLDEST_UPDATED, null, GistVisibility.PRIVATE)
        } returns ApiResult.Success(page("restored"))
        val viewModel = createViewModel(repository, savedState)

        viewModel.init("octocat")

        assertEquals(GistCategory.FORKED, viewModel.state.value.selectedCategory)
        assertEquals(GistSort.OLDEST_UPDATED, viewModel.state.value.selectedSort)
        assertEquals(GistVisibility.PRIVATE, viewModel.state.value.selectedVisibility)
        assertEquals(GistVisibility.PRIVATE, viewModel.state.value.current.loadedVisibility)
        assertEquals(listOf("restored"), viewModel.state.value.items.map { it.id })
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `非法恢复值回落到默认条件并修正保存值`() = runTest {
        val savedState = SavedStateHandle(mapOf(
            "gist.selectedCategory" to "invalid",
            "gist.selectedSort" to "invalid",
            "gist.selectedVisibility" to "invalid",
        ))
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ALL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL)
        } returns ApiResult.Success(page("default"))
        val viewModel = createViewModel(repository, savedState)

        viewModel.init(null)

        assertEquals("ALL", savedState.get<String>("gist.selectedCategory"))
        assertEquals("RECENTLY_UPDATED", savedState.get<String>("gist.selectedSort"))
        assertEquals("ALL", savedState.get<String>("gist.selectedVisibility"))
        assertEquals(listOf("default"), viewModel.state.value.items.map { it.id })
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `切换可见性重置首屏并将条件传给刷新和下一页`() = runTest {
        val repository = mockk<GistRepository>()
        val gate = CompletableDeferred<Unit>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL)
        } returns ApiResult.Success(page("old", hasNext = true, cursor = "old-cursor"))
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE)
        } coAnswers {
            gate.await()
            ApiResult.Success(page("private", hasNext = true, cursor = "c1"))
        }
        coEvery {
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, GistVisibility.PRIVATE)
        } returns ApiResult.Success(page("fresh-private", hasNext = true, cursor = "c2"))
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c2", GistVisibility.PRIVATE)
        } returns ApiResult.Success(page("next-private"))
        val savedState = originalSavedState()
        val viewModel = createViewModel(repository, savedState)
        viewModel.init(null)

        viewModel.selectVisibility(GistVisibility.PRIVATE)
        viewModel.selectVisibility(GistVisibility.PRIVATE)
        viewModel.loadMore()
        assertTrue(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertNull(viewModel.state.value.current.nextCursor)
        assertFalse(viewModel.state.value.hasNextPage)
        assertEquals("PRIVATE", savedState.get<String>("gist.selectedVisibility"))
        gate.complete(Unit)
        viewModel.refresh()
        viewModel.loadMore()

        assertEquals(listOf("fresh-private", "next-private"), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.isLoadingMore)
        coVerify(exactly = 1) {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE)
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, GistVisibility.PRIVATE)
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c2", GistVisibility.PRIVATE)
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `分类切回只复用匹配可见性的分桶`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery { repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL) } returns ApiResult.Success(page("original-all"))
        coEvery { repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL) } returns ApiResult.Success(page("forked-all"))
        coEvery { repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE) } returns ApiResult.Success(page("original-private"))
        coEvery { repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE) } returns ApiResult.Success(page("forked-private"))
        val viewModel = createViewModel(repository)
        viewModel.init(null)
        viewModel.selectCategory(GistCategory.FORKED)
        viewModel.selectCategory(GistCategory.ORIGINAL)
        assertEquals(listOf("original-all"), viewModel.state.value.items.map { it.id })

        viewModel.selectVisibility(GistVisibility.PRIVATE)
        viewModel.selectCategory(GistCategory.FORKED)
        assertEquals(listOf("forked-private"), viewModel.state.value.items.map { it.id })
        viewModel.selectCategory(GistCategory.ORIGINAL)
        assertEquals(listOf("original-private"), viewModel.state.value.items.map { it.id })
        coVerify(exactly = 1) {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL)
            repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL)
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE)
            repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE)
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `另一个分类仍在旧条件加载时切回可以立即加载新条件`() = runTest {
        val repository = mockk<GistRepository>()
        val old = CompletableDeferred<ApiResult<GistPage>>()
        coEvery { repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL) } coAnswers { old.await() }
        coEvery { repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL) } returns ApiResult.Success(page("forked-all"))
        coEvery { repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE) } returns ApiResult.Success(page("forked-private"))
        coEvery { repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE) } returns ApiResult.Success(page("original-private"))
        val viewModel = createViewModel(repository)
        viewModel.init(null)
        viewModel.selectCategory(GistCategory.FORKED)
        viewModel.selectVisibility(GistVisibility.PRIVATE)
        viewModel.selectCategory(GistCategory.ORIGINAL)

        assertEquals(listOf("original-private"), viewModel.state.value.items.map { it.id })
        old.complete(ApiResult.Success(page("obsolete")))
        assertEquals(listOf("original-private"), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.isLoading)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `可见性A到B到A忽略旧成功和旧错误且不结束新加载`() = runTest {
        for (obsolete in listOf(ApiResult.Success(page("obsolete")), ApiResult.Failure(ApiError.NetworkError))) {
            val repository = mockk<GistRepository>()
            val old = CompletableDeferred<ApiResult<GistPage>>()
            val latest = CompletableDeferred<ApiResult<GistPage>>()
            var calls = 0
            coEvery {
                repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL)
            } coAnswers { if (++calls == 1) old.await() else latest.await() }
            coEvery {
                repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE)
            } returns ApiResult.Success(page("private"))
            val bus = ErrorEventBus()
            val viewModel = createViewModel(repository, errorEventBus = bus)
            bus.events.test {
                viewModel.init(null)
                viewModel.selectVisibility(GistVisibility.PRIVATE)
                viewModel.selectVisibility(GistVisibility.ALL)
                old.complete(obsolete)
                assertTrue(viewModel.state.value.isLoading)
                assertTrue(viewModel.state.value.items.isEmpty())
                expectNoEvents()

                latest.complete(ApiResult.Success(page("latest")))
                assertEquals(listOf("latest"), viewModel.state.value.items.map { it.id })
                assertFalse(viewModel.state.value.isLoading)
            }
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `排序A到B到A旧请求不能覆盖最新结果`() = runTest {
        val repository = mockk<GistRepository>()
        val old = CompletableDeferred<ApiResult<GistPage>>()
        var calls = 0
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL)
        } coAnswers { if (++calls == 1) old.await() else ApiResult.Success(page("latest")) }
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.OLDEST_CREATED, null, GistVisibility.ALL)
        } returns ApiResult.Success(page("oldest"))
        val viewModel = createViewModel(repository)
        viewModel.init(null)
        viewModel.selectSort(GistSort.OLDEST_CREATED)
        viewModel.selectSort(GistSort.RECENTLY_UPDATED)
        old.complete(ApiResult.Success(page("obsolete")))
        assertEquals(listOf("latest"), viewModel.state.value.items.map { it.id })
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `刷新期间切换筛选后旧刷新错误不影响当前结果和提示`() = runTest {
        val repository = mockk<GistRepository>()
        val old = CompletableDeferred<ApiResult<GistPage>>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.ALL)
        } returns ApiResult.Success(page("all"))
        coEvery {
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, GistVisibility.ALL)
        } coAnswers { old.await() }
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PUBLIC)
        } returns ApiResult.Success(page("public"))
        val bus = ErrorEventBus()
        val viewModel = createViewModel(repository, errorEventBus = bus)
        bus.events.test {
            viewModel.init(null)
            viewModel.refresh()
            viewModel.selectVisibility(GistVisibility.PUBLIC)
            viewModel.selectVisibility(GistVisibility.ALL)
            old.complete(ApiResult.Failure(ApiError.NetworkError))
            assertEquals(listOf("all"), viewModel.state.value.items.map { it.id })
            assertFalse(viewModel.state.value.isRefreshing)
            assertFalse(viewModel.state.value.loadFailed)
            expectNoEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `刷新后相同游标的新分页不被旧分页错误清除或追加`() = runTest {
        for (obsolete in listOf(ApiResult.Success(page("obsolete")), ApiResult.Failure(ApiError.NetworkError))) {
            val repository = mockk<GistRepository>()
            val old = CompletableDeferred<ApiResult<GistPage>>()
            val latest = CompletableDeferred<ApiResult<GistPage>>()
            var calls = 0
            coEvery { repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null) } returns ApiResult.Success(page("initial", true, "same-cursor"))
            coEvery { repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED) } returns ApiResult.Success(page("fresh", true, "same-cursor"))
            coEvery {
                repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "same-cursor")
            } coAnswers { if (++calls == 1) old.await() else latest.await() }
            val bus = ErrorEventBus()
            val viewModel = createViewModel(repository, errorEventBus = bus)
            bus.events.test {
                viewModel.init(null)
                viewModel.loadMore()
                viewModel.refresh()
                viewModel.loadMore()
                old.complete(obsolete)
                assertTrue(viewModel.state.value.isLoadingMore)
                assertEquals(listOf("fresh"), viewModel.state.value.items.map { it.id })
                expectNoEvents()
                latest.complete(ApiResult.Success(GistPage(listOf(gist("fresh"), gist("next")), false, null)))
                assertEquals(listOf("fresh", "next"), viewModel.state.value.items.map { it.id })
                assertFalse(viewModel.state.value.isLoadingMore)
            }
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `当前条件首屏和分页失败均可重试并发出错误`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null, GistVisibility.PRIVATE)
        } returns ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(page("first", true, "c1"))
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, "c1", GistVisibility.PRIVATE)
        } returns ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(page("next"))
        val bus = ErrorEventBus()
        val viewModel = createViewModel(
            repository,
            SavedStateHandle(mapOf(
                "gist.selectedCategory" to "ORIGINAL",
                "gist.selectedVisibility" to "PRIVATE",
            )),
            bus,
        )
        bus.events.test {
            viewModel.init(null)
            assertTrue(viewModel.state.value.loadFailed)
            assertEquals(BannerEvent.Error(ApiError.NetworkError), awaitItem())
            viewModel.retry()
            assertFalse(viewModel.state.value.loadFailed)
            viewModel.loadMore()
            assertEquals(BannerEvent.Error(ApiError.NetworkError), awaitItem())
            assertEquals(listOf("first"), viewModel.state.value.items.map { it.id })
            assertEquals("c1", viewModel.state.value.current.nextCursor)
            assertTrue(viewModel.state.value.hasNextPage)
            assertFalse(viewModel.state.value.isLoadingMore)
            viewModel.loadMore()
            assertEquals(listOf("first", "next"), viewModel.state.value.items.map { it.id })
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `Gist列表变更事件强制刷新当前分类并清空其他分类快照`() = runTest {
        val repository = mockk<GistRepository>()
        coEvery {
            repository.getGists(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("old"))
        coEvery {
            repository.getGistsFresh(null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED)
        } returns ApiResult.Success(page("fresh"))
        coEvery {
            repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null)
        } returns ApiResult.Success(page("forked"))
        val updateBus = GistUpdateEventBus()
        val viewModel = createViewModel(repository, gistUpdateEventBus = updateBus)
        viewModel.init(null)
        viewModel.selectCategory(GistCategory.FORKED)
        viewModel.selectCategory(GistCategory.ORIGINAL)

        updateBus.emitListChanged()
        advanceUntilIdle()

        assertEquals(listOf("fresh"), viewModel.state.value.items.map { it.id })
        viewModel.selectCategory(GistCategory.FORKED)
        assertEquals(listOf("forked"), viewModel.state.value.items.map { it.id })
        coVerify(exactly = 2) {
            repository.getGists(null, GistCategory.FORKED, GistSort.RECENTLY_UPDATED, null)
        }
        viewModel.viewModelScope.cancel()
    }

    private fun createViewModel(
        repository: GistRepository,
        savedStateHandle: SavedStateHandle = originalSavedState(),
        errorEventBus: ErrorEventBus = ErrorEventBus(),
        gistUpdateEventBus: GistUpdateEventBus = GistUpdateEventBus(),
    ) = GistViewModel(repository, errorEventBus, savedStateHandle, gistUpdateEventBus)

    private fun originalSavedState() = SavedStateHandle(
        mapOf("gist.selectedCategory" to "ORIGINAL"),
    )

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
