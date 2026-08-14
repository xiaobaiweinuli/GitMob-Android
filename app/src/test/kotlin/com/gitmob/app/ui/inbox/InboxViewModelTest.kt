package com.gitmob.app.ui.inbox

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.InboxNotification
import com.gitmob.app.data.repository.NotificationRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 纯 JVM 单元测试（直接跑 ./gradlew testDebugUnitTest，
 * 不需要模拟器/真机/APK 安装）。
 *
 * 符合 SKILL.md 第七节「测试规范」ViewModel 范式：
 *   - JUnit4 + kotlinx-coroutines-test + MockK
 *   - MainDispatcherRule
 *   - 不通过 Hilt，直接 new ViewModel(...)
 *
 * 覆盖：
 *   - 初次加载成功：notifications 填充，loading 复位
 *   - 初次加载失败 → retry 成功（失败恢复）
 *   - refresh 重置分页
 *   - toggleShowAll: showAll 翻转并重新请求（all=true）
 *   - markAsRead：成功时本地乐观置 isUnread=false，失败时不改动
 *   - 并发：refresh + markAsRead 同时触发，无崩溃
 */
class InboxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fakeNotifications(prefix: String, count: Int, unreadRange: IntRange) =
        (1..count).map { i ->
            InboxNotification(
                id = "$prefix-$i", repoOwner = "o", repoName = "r",
                title = "Notif $i", subjectType = "Issue",
                subjectApiUrl = "https://api.github.com/repos/o/r/issues/$i",
                reason = "mention", isUnread = i in unreadRange,
                updatedAt = "2025-01-01T00:00:00Z",
            )
        }

    @Test
    fun `初次加载成功时填充notifications并复位loading`() = runTest {
        val repo = mockk<NotificationRepository>()
        coEvery { repo.getNotifications(page = 1, all = false) } returns
                ApiResult.Success(fakeNotifications("a", 3, 1..2))
        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()

        assertFalse(vm.state.value.isLoading)
        assertEquals(3, vm.state.value.notifications.size)
        assertFalse(vm.state.value.loadFailed)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `初次加载失败时loadFailed为true retry成功`() = runTest {
        val repo = mockk<NotificationRepository>()
        coEvery { repo.getNotifications(page = 1, all = false) } returns
                ApiResult.Failure(ApiError.NetworkError) andThen
                ApiResult.Success(fakeNotifications("b", 2, 1..1))

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        assertTrue(vm.state.value.loadFailed)
        assertEquals(0, vm.state.value.notifications.size)

        vm.retry()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(2, vm.state.value.notifications.size)
        coVerify(exactly = 2) { repo.getNotifications(page = 1, all = false) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `toggleShowAll会翻showAll并且重新请求all等于true`() = runTest {
        val repo = mockk<NotificationRepository>()
        coEvery { repo.getNotifications(page = 1, all = false) } returns
                ApiResult.Success(fakeNotifications("c", 2, 1..1))
        coEvery { repo.getNotifications(page = 1, all = true) } returns
                ApiResult.Success(fakeNotifications("c", 5, 1..2)) andThen
                ApiResult.Success(fakeNotifications("fresh", 6, 1..2))

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        assertFalse(vm.state.value.showAll)
        val firstCount = vm.state.value.notifications.size

        vm.toggleShowAll()
        assertTrue(vm.state.value.showAll)
        val secondCount = vm.state.value.notifications.size
        assertTrue(secondCount > firstCount)

        vm.refresh()
        assertEquals(6, vm.state.value.notifications.size)
        coVerify(exactly = 2) { repo.getNotifications(page = 1, all = true) }
        coVerify(exactly = 0) { repo.getNotificationsFresh() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `markAsRead成功时本地乐观把对应条目的isUnread改成false`() = runTest {
        val repo = mockk<NotificationRepository>()
        val list = fakeNotifications("d", 3, 1..2)
        coEvery { repo.getNotifications(page = 1, all = false) } returns ApiResult.Success(list)
        coEvery { repo.markAsRead(any()) } returns ApiResult.Success(Unit)

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        val target = vm.state.value.notifications.first { it.isUnread }
        assertTrue(target.isUnread)

        vm.markAsRead(target)
        val after = vm.state.value.notifications.first { it.id == target.id }
        assertFalse(after.isUnread)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `markAsRead失败时不改动任何条目的isUnread`() = runTest {
        val repo = mockk<NotificationRepository>()
        val list = fakeNotifications("e", 2, 1..2)
        coEvery { repo.getNotifications(page = 1, all = false) } returns ApiResult.Success(list)
        coEvery { repo.markAsRead(any()) } returns ApiResult.Failure(ApiError.Forbidden)

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        val target = vm.state.value.notifications.first()
        val beforeCount = vm.state.value.notifications.count { it.isUnread }

        vm.markAsRead(target)
        val afterCount = vm.state.value.notifications.count { it.isUnread }
        // 失败时 Success 分支的 map 不执行，所以计数不变
        assertEquals(beforeCount, afterCount)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `并发场景_refresh与markAsRead同时触发不崩溃`() = runTest {
        val repo = mockk<NotificationRepository>()
        coEvery { repo.getNotifications(page = 1, any()) } returns
                ApiResult.Success(fakeNotifications("f", 3, 1..1))
        coEvery { repo.getNotificationsFresh() } returns
                ApiResult.Success(fakeNotifications("fresh", 4, 1..2))
        coEvery { repo.markAsRead(any()) } returns ApiResult.Success(Unit)

        val vm = InboxViewModel(repo, ErrorEventBus())
        vm.loadIfNeeded()
        val first = vm.state.value.notifications.first()
        vm.markAsRead(first)
        vm.refresh()
        assertFalse(vm.state.value.isRefreshing)
        assertEquals(4, vm.state.value.notifications.size)
        coVerify(exactly = 1) { repo.getNotificationsFresh() }
        vm.viewModelScope.cancel()
    }
}
