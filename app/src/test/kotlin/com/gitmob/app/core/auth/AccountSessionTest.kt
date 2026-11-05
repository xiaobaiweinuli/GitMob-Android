package com.gitmob.app.core.auth

import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.testutil.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class AccountSessionTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `切换立即取消旧账号工作且旧上下文不能回填缓存`() = runTest {
        val session = AccountSession()
        val account = GitHubAccount("1", 1, "U_1", "first")
        session.replace(listOf(account), account.key)
        val previous = session.epoch
        val scope = session.viewModelScope()
        val work = scope.launch { awaitCancellation() }
        val cache = MemoryCache<String, String>(60_000)
        withContext(AccountContext(previous)) { cache.set("viewer", "first") }

        val next = GitHubAccount("2", 2, "U_2", "second")
        session.replace(listOf(account, next), next.key)
        assertFalse(previous.isCurrent)
        assertTrue(work.isCancelled)
        withContext(AccountContext(session.epoch)) { assertNull(cache.get("viewer")) }
        var cancelled = false
        try {
            withContext(AccountContext(previous)) { cache.set("viewer", "late first") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertNull(AccountContext.current())
        session.epoch.job.cancel()
    }

    @Test
    fun `退出后的账号列表保留但不自动激活剩余账号`() {
        val session = AccountSession()
        val account = GitHubAccount("2", 2, "U_2", "remaining")
        session.replace(listOf(account), null)
        assertTrue(session.state.value.isLoaded)
        assertEquals(listOf(account), session.state.value.accounts)
        assertNull(session.state.value.activeAccount)
        assertNull(session.epoch.accountKey)
        session.epoch.job.cancel()
    }

    @Test
    fun `缓存拒绝无上下文和匿名上下文而不是借用活动账号`() = runTest {
        val session = AccountSession()
        val anonymous = session.epoch
        val account = GitHubAccount("1", 1, "U_1", "first")
        val cache = MemoryCache<String, String>(60_000)
        withContext(AccountContext(anonymous)) {
            assertThrows(IllegalStateException::class.java) { cache.get("viewer") }
        }
        session.replace(listOf(account), account.key)
        assertThrows(IllegalStateException::class.java) { cache.set("viewer", "unbound") }
        assertThrows(IllegalStateException::class.java) { cache.invalidateAll() }
        session.epoch.job.cancel()
    }

    @Test
    fun `结束会话释放缓存分区且旧失效不能清理新缓存`() = runTest {
        val session = AccountSession()
        val first = GitHubAccount("1", 1, "U_1", "first")
        val second = GitHubAccount("2", 2, "U_2", "second")
        session.replace(listOf(first, second), first.key)
        val old = session.epoch
        val cache = MemoryCache<String, String>(60_000)
        withContext(AccountContext(old)) { cache.set("viewer", "first") }
        val stores = MemoryCache::class.java.getDeclaredField("stores").apply { isAccessible = true }
        assertEquals(1, (stores.get(cache) as Map<*, *>).size)

        session.replace(listOf(first, second), second.key)
        assertTrue((stores.get(cache) as Map<*, *>).isEmpty())
        withContext(AccountContext(session.epoch)) { cache.set("viewer", "second") }
        var cancelled = false
        try {
            withContext(AccountContext(old)) { cache.invalidateAll() }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        withContext(AccountContext(session.epoch)) { assertEquals("second", cache.get("viewer")) }
        session.epoch.job.cancel()
        assertTrue((stores.get(cache) as Map<*, *>).isEmpty())
    }

    @Test
    fun `新状态发布前旧工作已取消并拒绝旧上下文`() = runTest {
        val session = AccountSession()
        val first = GitHubAccount("1", 1, "U_1", "first")
        val second = GitHubAccount("2", 2, "U_2", "second")
        session.replace(listOf(first, second), first.key)
        val old = session.epoch
        var cancelledBeforePublish = false
        old.job.invokeOnCompletion {
            cancelledBeforePublish = !old.isCurrent && session.state.value.activeAccount == first
        }

        session.replace(listOf(first, second), second.key)

        assertTrue(cancelledBeforePublish)
        assertEquals(second, session.state.value.activeAccount)
        assertEquals(session.epoch.id, session.state.value.sessionId)
        session.epoch.job.cancel()
    }
}
