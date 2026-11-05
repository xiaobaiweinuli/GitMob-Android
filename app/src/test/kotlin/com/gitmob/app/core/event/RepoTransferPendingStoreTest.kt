package com.gitmob.app.core.event

import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.data.model.GitHubAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoTransferPendingStoreTest {
    @Test
    fun `待转移记录按会话隔离并在结束时释放且拒绝旧清理`() = runTest {
        val session = AccountSession()
        val first = GitHubAccount("1", 1, "U_1", "first")
        val second = GitHubAccount("2", 2, "U_2", "second")
        session.replace(listOf(first, second), first.key)
        val old = session.epoch
        val store = RepoTransferPendingStore()
        val transfer = PendingRepositoryTransfer("R1", "first", "repo", "target", "renamed")
        assertThrows(IllegalStateException::class.java) { store.mark(transfer) }
        withContext(AccountContext(old)) {
            store.mark(transfer)
            assertEquals(transfer, store.find("R1", "unused", "unused"))
            assertEquals(transfer, store.find(null, "FIRST", "REPO"))
        }
        assertEquals(mapOf("R1" to transfer), store.transfersFor(old).first())
        val partitions = RepoTransferPendingStore::class.java.getDeclaredField("partitions").apply { isAccessible = true }

        session.replace(listOf(first, second), second.key)
        assertTrue((partitions.get(store) as Map<*, *>).isEmpty())
        withContext(AccountContext(session.epoch)) {
            assertNull(store.find("R1", "first", "repo"))
            store.mark(transfer.copy(owner = "second"))
        }
        var cancelled = false
        try { withContext(AccountContext(old)) { store.clear("R1") } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        withContext(AccountContext(session.epoch)) { assertEquals("second", store.find("R1", "", "")?.owner) }
        session.epoch.job.cancel()
        assertTrue((partitions.get(store) as Map<*, *>).isEmpty())
    }
}
