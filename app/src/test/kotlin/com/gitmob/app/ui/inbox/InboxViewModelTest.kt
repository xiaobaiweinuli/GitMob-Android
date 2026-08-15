package com.gitmob.app.ui.inbox

import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.InboxNotification
import com.gitmob.app.data.model.InboxReadFilter
import com.gitmob.app.data.model.PagedNotifications
import com.gitmob.app.data.repository.NotificationRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeNotifications(prefix: String, count: Int, unreadRange: IntRange) =
        (1..count).map { i ->
            InboxNotification(
                id = "$prefix-$i",
                repoOwner = "o",
                repoName = "r",
                title = "Notif $i",
                subjectType = "Issue",
                subjectApiUrl = "https://api.github.com/repos/o/r/issues/$i",
                reason = "mention",
                isUnread = i in unreadRange,
                updatedAt = "2025-01-01T00:00:00Z",
            )
        }

    private fun page(items: List<InboxNotification>, nextSourcePage: Int = 2, hasNextPage: Boolean = false) =
        ApiResult.Success(PagedNotifications(items, nextSourcePage, hasNextPage))

    @Test
    fun `initial load populates notifications`() = runTest {
        val repo = mockk<NotificationRepository>()
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.UNREAD)
        } returns page(fakeNotifications("a", 3, 1..2))

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()

        assertFalse(vm.state.value.isLoading)
        assertEquals(3, vm.state.value.notifications.size)
        assertEquals(InboxReadFilter.UNREAD, vm.state.value.readFilter)
        assertFalse(vm.state.value.loadFailed)
    }

    @Test
    fun `failed load can be retried`() = runTest {
        val repo = mockk<NotificationRepository>()
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.UNREAD)
        } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError),
            page(fakeNotifications("b", 2, 1..1)),
        )

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        assertTrue(vm.state.value.loadFailed)

        vm.retry()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(2, vm.state.value.notifications.size)
        coVerify(exactly = 2) {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.UNREAD)
        }
    }

    @Test
    fun `selecting read filter reloads immediately`() = runTest {
        val repo = mockk<NotificationRepository>()
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.UNREAD)
        } returns page(fakeNotifications("unread", 2, 1..2))
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.READ)
        } returns page(fakeNotifications("read", 2, IntRange.EMPTY))

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        vm.setReadFilter(InboxReadFilter.READ)

        assertEquals(InboxReadFilter.READ, vm.state.value.readFilter)
        assertEquals(listOf("read-1", "read-2"), vm.state.value.notifications.map { it.id })
        coVerify(exactly = 1) {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.READ)
        }
    }

    @Test
    fun `marking unread removes item in unread filter`() = runTest {
        val repo = mockk<NotificationRepository>()
        val list = fakeNotifications("unread", 3, 1..2)
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.UNREAD)
        } returns page(list)
        coEvery { repo.markAsRead(any()) } returns ApiResult.Success(Unit)

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        vm.markAsRead(list.first())

        assertTrue(vm.state.value.notifications.none { it.id == list.first().id })
    }

    @Test
    fun `marking unread keeps item but clears flag in all filter`() = runTest {
        val repo = mockk<NotificationRepository>()
        val list = fakeNotifications("all", 2, 1..1)
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.ALL)
        } returns page(list)
        coEvery { repo.markAsRead(any()) } returns ApiResult.Success(Unit)

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.setReadFilter(InboxReadFilter.ALL)
        vm.markAsRead(list.first())

        assertFalse(vm.state.value.notifications.first().isUnread)
    }

    @Test
    fun `load more uses next source page`() = runTest {
        val repo = mockk<NotificationRepository>()
        val first = fakeNotifications("first", 2, 1..2)
        val second = fakeNotifications("second", 1, 1..1)
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.UNREAD)
        } returns page(first, nextSourcePage = 4, hasNextPage = true)
        coEvery {
            repo.getNotifications(sourcePage = 4, filter = InboxReadFilter.UNREAD)
        } returns page(second, nextSourcePage = 5, hasNextPage = false)

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        vm.loadMore()

        assertEquals(3, vm.state.value.notifications.size)
        coVerify(exactly = 1) {
            repo.getNotifications(sourcePage = 4, filter = InboxReadFilter.UNREAD)
        }
    }

    @Test
    fun `stale load more result is ignored after filter changes`() = runTest {
        val repo = mockk<NotificationRepository>()
        val stalePage = CompletableDeferred<ApiResult<PagedNotifications>>()
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.UNREAD)
        } returns page(fakeNotifications("unread", 1, 1..1), nextSourcePage = 2, hasNextPage = true)
        coEvery {
            repo.getNotifications(sourcePage = 2, filter = InboxReadFilter.UNREAD)
        } coAnswers { stalePage.await() }
        coEvery {
            repo.getNotifications(sourcePage = 1, filter = InboxReadFilter.READ)
        } returns page(fakeNotifications("read", 1, IntRange.EMPTY))

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        vm.loadMore()
        vm.setReadFilter(InboxReadFilter.READ)
        stalePage.complete(page(fakeNotifications("stale", 1, 1..1)))
        advanceUntilIdle()

        assertEquals(listOf("read-1"), vm.state.value.notifications.map { it.id })
    }
}
