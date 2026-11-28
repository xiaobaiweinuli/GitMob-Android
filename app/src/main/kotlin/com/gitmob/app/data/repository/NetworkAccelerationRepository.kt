package com.gitmob.app.data.repository

import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.RawUrlPolicy
import com.gitmob.app.core.network.readCancellable
import com.gitmob.app.core.preferences.RawAccelerationPreferenceStore
import com.gitmob.app.core.preferences.RawAccelerationService
import com.gitmob.app.core.preferences.RawAccelerationSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

sealed interface AccelerationProbeResult {
    data class Success(val millis: Long) : AccelerationProbeResult
    data object Timeout : AccelerationProbeResult
    data object Failed : AccelerationProbeResult
}

@Singleton
class NetworkAccelerationRepository @Inject constructor(
    private val preferences: RawAccelerationPreferenceStore,
    client: OkHttpClient,
    private val policy: RawUrlPolicy,
) {
    val settings = preferences.settings
    private val probeClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS)
        .followSslRedirects(false).cache(null).build()

    suspend fun initialize() = safeCall { preferences.initialize() }

    suspend fun save(id: String?, name: String, address: String) = safeCall {
        val title = name.trim()
        if (title.isEmpty() || title.length > 40) throw UserVisibleException(R.string.acceleration_invalid_name)
        val prefix = policy.normalizePrefix(address) ?: throw UserVisibleException(R.string.acceleration_invalid_address)
        preferences.update { current ->
            if (current.services.any { it.id != id && it.prefix == prefix })
                throw UserVisibleException(R.string.acceleration_duplicate)
            if (id != null && current.services.none { it.id == id }) return@update current
            val service = RawAccelerationService(id ?: UUID.randomUUID().toString(), title, prefix)
            current.copy(services = if (id == null) current.services + service
                else current.services.map { if (it.id == id) service else it })
        }
    }

    suspend fun select(id: String) = safeCall {
        preferences.update { current ->
            if (current.services.none { it.id == id }) current else current.copy(selectedId = id, enabled = true)
        }
    }

    suspend fun enable(enabled: Boolean) = safeCall {
        preferences.update { it.copy(enabled = enabled && it.services.any { s -> s.id == it.selectedId }) }
    }

    suspend fun delete(id: String) = safeCall {
        preferences.update { it.copy(services = it.services.filterNot { s -> s.id == id },
            selectedId = it.selectedId.takeUnless { selected -> selected == id },
            enabled = it.enabled && it.selectedId != id) }
    }

    /** Candidate requests never change the application's selected service. */
    suspend fun testAll(snapshot: RawAccelerationSettings, result: (String, AccelerationProbeResult) -> Unit) = safeCall {
        val semaphore = Semaphore(3)
        val results = coroutineScope {
            snapshot.services.map { service -> async {
                val measured = semaphore.withPermit { probe(service) }
                result(service.id, measured)
                service.id to measured
            } }.awaitAll().toMap()
        }
        coroutineContext.ensureActive()
        preferences.update { current ->
            if (current.revision != snapshot.revision) return@update current
            val winner = fastestService(results, current.selectedId) ?: return@update current
            current.copy(selectedId = winner, enabled = true)
        }
        results
    }

    private suspend fun probe(service: RawAccelerationService): AccelerationProbeResult {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val request = Request.Builder().url(policy.rewrite(PROBE_URL, service.prefix))
                .cacheControl(CacheControl.FORCE_NETWORK).build()
            val valid = probeClient.newCall(request).readCancellable { response ->
                val source = response.body.source()
                response.code == 200 && !source.request((PROBE_BODY.size + 1).toLong()) &&
                    source.readByteArray().contentEquals(PROBE_BODY)
            }
            if (valid) AccelerationProbeResult.Success(start.elapsedNow().inWholeMilliseconds.coerceAtLeast(1))
            else AccelerationProbeResult.Failed
        } catch (_: InterruptedIOException) {
            coroutineContext.ensureActive()
            AccelerationProbeResult.Timeout
        } catch (_: IOException) {
            coroutineContext.ensureActive()
            AccelerationProbeResult.Failed
        }
    }

    companion object {
        internal const val PROBE_URL = "https://raw.githubusercontent.com/octocat/Hello-World/7fd1a60b01f91b314f59955a4e4d4e80d8edf11d/README"
        private val PROBE_BODY = "Hello World!\n".toByteArray()
    }
}

internal fun fastestService(results: Map<String, AccelerationProbeResult>, selectedId: String?): String? {
    val successes = results.mapNotNull { (id, result) -> (result as? AccelerationProbeResult.Success)?.let { id to it.millis } }
    val fastest = successes.minOfOrNull { it.second } ?: return null
    return successes.firstOrNull { it.first == selectedId && it.second == fastest }?.first
        ?: successes.first { it.second == fastest }.first
}
