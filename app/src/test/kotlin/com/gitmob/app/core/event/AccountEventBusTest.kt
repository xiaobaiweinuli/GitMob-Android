package com.gitmob.app.core.event

import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.data.model.GitHubAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountEventBusTest {
    @Test
    fun `事件拒绝无上下文且旧来源不能发送到新账号`() = runTest {
        val session = AccountSession()
        val first = GitHubAccount("1", 1, "U_1", "first")
        val second = GitHubAccount("2", 2, "U_2", "second")
        session.replace(listOf(first, second), first.key)
        val old = session.epoch
        val bus = AccountEventBus<String>(4)
        var rejected = false
        try { bus.emit("unbound") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)

        session.replace(listOf(first, second), second.key)
        val next = async(start = CoroutineStart.UNDISPATCHED) { bus.eventsFor(session.epoch).first() }
        var cancelled = false
        try { bus.emit("late", old) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(next.isCompleted)
        withContext(AccountContext(session.epoch)) { bus.emit("second") }
        assertEquals("second", next.await())
        session.epoch.job.cancel()
    }

    @Test
    fun `切换后缓冲事件不会送给新订阅者或继续更新旧订阅者`() = runTest {
        val session = AccountSession()
        val first = GitHubAccount("1", 1, "U_1", "first")
        val second = GitHubAccount("2", 2, "U_2", "second")
        session.replace(listOf(first, second), first.key)
        val bus = AccountEventBus<String>(4)
        val received = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.eventsFor(session.epoch).collect {
                received += it
                release.await()
            }
        }
        bus.emit("first", session.epoch)
        runCurrent()
        bus.emit("buffered first", session.epoch)

        session.replace(listOf(first, second), second.key)
        val next = async(start = CoroutineStart.UNDISPATCHED) { bus.eventsFor(session.epoch).first() }
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("first"), received)
        assertFalse(next.isCompleted)
        bus.emit("second", session.epoch)
        assertEquals("second", next.await())
        session.epoch.job.cancel()
    }
}
