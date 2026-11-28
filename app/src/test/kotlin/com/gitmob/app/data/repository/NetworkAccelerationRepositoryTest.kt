package com.gitmob.app.data.repository

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.RawUrlPolicy
import com.gitmob.app.core.preferences.*
import com.gitmob.app.testutil.NetworkPreferencesFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList

class NetworkAccelerationRepositoryTest {
    private val policy = RawUrlPolicy()
    private val preferences = RawAccelerationPreferenceStore(NetworkPreferencesFixture())
    private val requests = CopyOnWriteArrayList<Request>()
    private fun repository(answer: (Request) -> String = { "Hello World!\n" }): NetworkAccelerationRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer(request).toResponseBody()).build()
        }.build()
        return NetworkAccelerationRepository(preferences, client, policy)
    }

    @Test fun crudNormalizesDeduplicatesAndDeletingSelectionDisables() = runTest {
        val repo = repository()
        assertTrue(repo.save(null, " First ", "proxy.example/path") is ApiResult.Success)
        val first = preferences.settings.value!!.services.single()
        assertEquals("First", first.name)
        assertEquals("https://proxy.example/path/", first.prefix)
        assertFalse(preferences.settings.value!!.enabled)
        assertTrue(repo.save(null, "Duplicate", "https://PROXY.example/path/") is ApiResult.Failure)
        repo.select(first.id)
        assertTrue(preferences.settings.value!!.enabled)
        repo.enable(false)
        assertEquals(first.id, preferences.settings.value!!.selectedId)
        repo.enable(true)
        repo.delete(first.id)
        assertNull(preferences.settings.value!!.selectedId)
        assertFalse(preferences.settings.value!!.enabled)
        assertTrue(preferences.settings.value!!.services.isEmpty())
    }

    @Test fun validatesResponseContentAndSelectsSuccessWithoutCredentials() = runTest {
        val repo = repository { if (it.url.host == "good.example") "Hello World!\n" else "<html>blocked</html>" }
        repo.save(null, "Good", "good.example/path")
        repo.save(null, "Fake", "fake.example")
        val snapshot = preferences.initialize()
        val result = repo.testAll(snapshot) { _, _ -> } as ApiResult.Success
        assertTrue(result.data[snapshot.services[0].id] is AccelerationProbeResult.Success)
        assertEquals(AccelerationProbeResult.Failed, result.data[snapshot.services[1].id])
        assertEquals(snapshot.services[0].id, preferences.settings.value!!.selectedId)
        assertTrue(preferences.settings.value!!.enabled)
        requests.forEach {
            assertNull(it.header("Authorization"))
            assertNull(it.header("Cookie"))
            assertTrue(it.url.toString().endsWith(NetworkAccelerationRepository.PROBE_URL))
        }
    }

    @Test fun allFailuresAndTimeoutsKeepPreviousChoiceAndEnablement() = runTest {
        val repo = repository { if (it.url.host == "timeout.example") throw InterruptedIOException() else "bad" }
        repo.save(null, "Bad", "bad.example")
        repo.save(null, "Timeout", "timeout.example")
        repo.select(preferences.initialize().services.first().id)
        repo.enable(false)
        val snapshot = preferences.initialize()
        val result = repo.testAll(snapshot) { _, _ -> } as ApiResult.Success
        assertEquals(snapshot, preferences.initialize())
        assertEquals(AccelerationProbeResult.Timeout, result.data[snapshot.services[1].id])
    }

    @Test fun changedConfigurationPreventsOldProbeFromOverwritingManualSelection() = runTest {
        val repo = repository()
        repo.save(null, "One", "one.example")
        repo.save(null, "Two", "two.example")
        val before = preferences.initialize()
        repo.select(before.services[1].id)
        repo.enable(false)
        val after = preferences.initialize()
        assertTrue(repo.testAll(before) { _, _ -> } is ApiResult.Success)
        assertEquals(after, preferences.initialize())
    }

    @Test fun tiesKeepCurrentSelectionAndFailuresCannotWin() {
        val results = linkedMapOf("a" to AccelerationProbeResult.Success(10), "b" to AccelerationProbeResult.Success(10), "c" to AccelerationProbeResult.Failed)
        assertEquals("b", fastestService(results, "b"))
        assertEquals("a", fastestService(results, "c"))
        assertNull(fastestService(mapOf("a" to AccelerationProbeResult.Timeout), "a"))
    }
}
