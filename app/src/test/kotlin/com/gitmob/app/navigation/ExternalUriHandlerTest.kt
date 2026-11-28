package com.gitmob.app.navigation

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalUriHandlerTest {
    private val launcher = mockk<ExternalUriLauncher>()
    private val session = AccountSession()
    private val errors = ErrorEventBus()
    private val url = "https://raw.githubusercontent.com/o/r/main/file.png"

    @Test fun awaitedOpenOnlySucceedsAfterTheActualLaunch() = runTest {
        val ready = CompletableDeferred<Unit>()
        coEvery { launcher.openUri(url) } coAnswers { ready.await() }
        val handler = ExternalUriHandler(launcher, backgroundScope, session.epoch, errors)
        val opened = async { handler.openExternalUrl(url) }
        runCurrent()
        assertFalse(opened.isCompleted)
        ready.complete(Unit)
        assertEquals(ApiResult.Success(Unit), opened.await())
        coVerify(exactly = 1) { launcher.openUri(url) }
    }

    @Test fun synchronousCallbackReportsFailureAfterWaiting() = runTest {
        val ready = CompletableDeferred<Unit>()
        coEvery { launcher.openUri(url) } coAnswers {
            ready.await()
            throw IOException("Settings unavailable")
        }
        val handler = ExternalUriHandler(launcher, backgroundScope, session.epoch, errors)
        val event = async(start = CoroutineStart.UNDISPATCHED) { errors.eventsFor(session.epoch).first() }
        handler.openUri(url)
        runCurrent()
        assertFalse(event.isCompleted)
        ready.complete(Unit)
        assertEquals(BannerEvent.Error(ApiError.NetworkError), event.await())
    }

    @Test fun disposingTheCompositionCancelsItsPendingLaunch() = runTest {
        val ready = CompletableDeferred<Unit>()
        var completed = false
        var cancelled = false
        coEvery { launcher.openUri(url) } coAnswers {
            try {
                ready.await()
                completed = true
            } finally {
                cancelled = !completed
            }
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val handler = ExternalUriHandler(launcher, scope, session.epoch, errors)
        val event = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { errors.eventsFor(session.epoch).first() }
        handler.openUri(url)
        runCurrent()
        scope.cancel()
        ready.complete(Unit)
        runCurrent()
        assertTrue(cancelled)
        assertFalse(completed)
        assertFalse(event.isCompleted)
    }

    @Test fun staleAccountCannotStartAnAwaitedLaunch() = runTest {
        val handler = ExternalUriHandler(launcher, backgroundScope, session.epoch, errors)
        session.replace(emptyList(), null)
        assertTrue(runCatching { handler.openExternalUri(url) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { launcher.openUri(any()) }
    }
}
