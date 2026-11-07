package com.gitmob.app.ui.common

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.RepoDocumentRepository
import com.gitmob.app.data.repository.RepoDocumentResource
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RepoDocumentResourcesTest {
    @get:Rule val accounts = AccountSessionRule()
    private val document = RepoDocumentContext("owner", "repo", "feature/docs", "docs/guide.md")
    private val repository = mockk<RepoDocumentRepository>()
    private val image = RepoDocumentResource(byteArrayOf(1, 2, 3), "image/png")

    @Test fun `closing page cancels pending request and rejects cached resources`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        coEvery { repository.load(document, "cached.png") } returns ApiResult.Success(image)
        coEvery { repository.load(document, "pending.png") } coAnswers {
            started.complete(Unit)
            try { CompletableDeferred<Unit>().await(); ApiResult.Success(image) }
            finally { stopped.complete(Unit) }
        }
        val page = RepoDocumentResources(document, accounts.session.epoch, repository)
        withTimeout(5_000) {
            page.load("cached.png")
            val pending = async { page.load("pending.png") }
            started.await()
            page.close()
            stopped.await()
            try { pending.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            try { page.load("cached.png"); fail("Expected cancellation") } catch (_: CancellationException) { }
        }
    }

    @Test fun `account replacement invalidates an old page even for cached images`() = runBlocking {
        coEvery { repository.load(document, "image.png") } returns ApiResult.Success(image)
        RepoDocumentResources(document, accounts.session.epoch, repository).use { page ->
            page.load("image.png")
            accounts.session.replace(emptyList(), null)
            try { page.load("image.png"); fail("Expected cancellation") } catch (_: CancellationException) { }
        }
        coVerify(exactly = 1) { repository.load(document, "image.png") }
    }

}
