package com.gitmob.app.data.repository

import com.gitmob.app.data.translation.*
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Singleton
class TranslationRepository internal constructor(
    private val store: TranslationPreferenceStore,
    private val client: TranslationClient,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long,
) {
    @Inject constructor(store: TranslationPreferenceStore, client: TranslationClient) : this(store, client, Dispatchers.Default, System::currentTimeMillis)

    val configuration: StateFlow<TranslationConfiguration?> = store.configuration
    private val globalSlots = Semaphore(2)
    private val services = mutableMapOf<Pair<TranslationProvider, TranslationCredentials>, ServiceRequests>()

    fun retryConfiguration() = store.retryConfiguration()

    suspend fun savePreferences(value: TranslationPreferences): Result<Unit> = result(TranslationProblem.STORAGE) { store.setPreferences(value) }
    suspend fun saveCredentials(provider: TranslationProvider, value: TranslationCredentials): Result<Unit> = result(TranslationProblem.STORAGE) {
        client.buildRequest("Hello, world!", TranslationPreferences(provider = provider), value)
        store.setCredentials(provider, value)
    }
    suspend fun document(target: TranslationTarget, provider: TranslationProvider): Result<TranslationDocument> = result {
        withContext(cpuDispatcher) { TranslationDocuments.create(target, provider) }
    }

    suspend fun translate(text: String, preferences: TranslationPreferences, credentials: TranslationCredentials): Result<String> = result {
        if (text.isBlank()) text else preserveSpacing(text, request(listOf(text.trim()), preferences, credentials, RequestBudget()) {}.single())
    }

    /** Each successful batch is delivered before the next batch finishes. No body cache crosses tasks/accounts. */
    fun translateDocument(document: TranslationDocument, completed: Map<Int, String>, preferences: TranslationPreferences, credentials: TranslationCredentials): Flow<TranslationUpdate> = channelFlow {
        val batches = withContext(cpuDispatcher) {
            val context = currentCoroutineContext()
            val groups = linkedMapOf<String, MutableList<IndexedValue<String>>>()
            document.parts.forEachIndexed { index, part ->
                context.ensureActive()
                groups.getOrPut(part.trim()) { mutableListOf() }.add(IndexedValue(index, part))
            }
            val pending = mutableListOf<TranslationUnit>()
            for ((text, occurrences) in groups) {
                context.ensureActive()
                val missing = linkedMapOf<Int, String>()
                var cached: String? = null
                for ((index, original) in occurrences) {
                    context.ensureActive()
                    if (index !in completed) missing[index] = original
                    else if (cached == null) cached = completed[index]
                }
                if (missing.isEmpty()) continue
                if (cached != null || text.isBlank()) {
                    val translated = cached?.trim().orEmpty()
                    send(TranslationUpdate.Batch(missing.mapValues {
                        context.ensureActive()
                        preserveSpacing(it.value, translated)
                    }))
                } else pending += TranslationUnit(text, missing)
            }
            planTranslationBatches(pending, preferences.provider, { context.ensureActive() }) { texts ->
                client.requestBytes(texts, preferences, credentials)
            }
        }
        // A bounded number of workers avoids launching a coroutine per document unit.
        val cursor = AtomicInteger()
        repeat(if (TranslationClient.supportsBatch(preferences.provider)) 2 else 1) {
            launch {
                while (true) {
                    val batch = batches.getOrNull(cursor.getAndIncrement()) ?: break
                    process(batch, preferences, credentials, RequestBudget(), { send(it) }) { values -> send(TranslationUpdate.Batch(values)) }
                }
            }
        }
    }

    private suspend fun process(units: List<TranslationUnit>, p: TranslationPreferences, c: TranslationCredentials, budget: RequestBudget, event: suspend (TranslationUpdate) -> Unit, deliver: suspend (Map<Int, String>) -> Unit) {
        val values = try { request(units.map { it.text }, p, c, budget, event) }
        catch (e: TranslationException) {
            val mappingSplit = e.problem == TranslationProblem.MAPPING && units.size > 1 && !budget.mappingSplit
            if (mappingSplit) budget.mappingSplit = true
            val split = e.problem == TranslationProblem.TOO_LONG || mappingSplit
            if (!split || budget.attempts >= 8) throw e
            if (units.size > 1) {
                val middle = units.size / 2
                process(units.take(middle), p, c, budget, event, deliver)
                process(units.drop(middle), p, c, budget, event, deliver)
            } else {
                val unit = units.single()
                val pieces = withContext(cpuDispatcher) {
                    currentCoroutineContext().ensureActive()
                    val bytes = unit.text.toByteArray(Charsets.UTF_8).size
                    if (bytes <= 16) throw e
                    TranslationDocuments.splitUtf8(unit.text, (bytes / 2).coerceAtLeast(8))
                }
                if (pieces.size < 2) throw e
                val translated = StringBuilder()
                for (piece in pieces) {
                    // Retain the original unit identity; only publish it once its split response is complete.
                    process(listOf(TranslationUnit(piece.trim(), mapOf(0 to piece))), p, c, budget, event) { translated.append(it.getValue(0)) }
                }
                val mapped = withContext(cpuDispatcher) {
                    val context = currentCoroutineContext()
                    val combined = translated.toString()
                    unit.originals.mapValues {
                        context.ensureActive()
                        preserveSpacing(it.value, combined)
                    }
                }
                deliver(mapped)
            }
            return
        }
        val mapped = withContext(cpuDispatcher) {
            val context = currentCoroutineContext()
            buildMap {
                units.zip(values).forEach { (unit, value) ->
                    unit.originals.forEach { (id, original) ->
                        context.ensureActive()
                        put(id, preserveSpacing(original, value))
                    }
                }
            }
        }
        deliver(mapped)
    }

    private suspend fun request(texts: List<String>, p: TranslationPreferences, c: TranslationCredentials, budget: RequestBudget, event: suspend (TranslationUpdate) -> Unit): List<String> {
        val service = synchronized(services) { services.getOrPut(p.provider to c) { ServiceRequests(if (TranslationClient.supportsBatch(p.provider)) 2 else 1) } }
        val interval = if (TranslationClient.supportsBatch(p.provider)) 300L else 1000L
        var retry = 0
        while (true) {
            try {
                return service.slots.withPermit {
                    while (true) {
                        val waitUntil = service.lock.withLock { maxOf(service.nextStart, service.cooldownUntil) }
                        val wait = waitUntil - now()
                        if (wait > 0) {
                            if (service.lock.withLock { service.cooldownUntil > now() }) event(TranslationUpdate.Waiting(waitUntil))
                            delay(wait)
                        }
                        globalSlots.acquire()
                        try {
                            val granted = service.lock.withLock {
                                if (now() < maxOf(service.nextStart, service.cooldownUntil)) false
                                else { service.nextStart = now() + interval; true }
                            }
                            if (!granted) continue
                            if (++budget.attempts > 8) throw TranslationException(TranslationProblem.RESPONSE)
                            event(TranslationUpdate.RequestStarted)
                            val response = client.translateBatch(texts, p, c)
                            if (response.size != texts.size) throw TranslationException(TranslationProblem.MAPPING)
                            return@withPermit response
                        } finally { globalSlots.release() }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
            } catch (e: TranslationException) {
                if (e.problem != TranslationProblem.LIMIT) throw e
                val backoff = e.retryAfterMillis ?: (3000L shl retry.coerceAtMost(2))
                val until = service.lock.withLock {
                    service.cooldownUntil = maxOf(service.cooldownUntil, now() + backoff.coerceIn(0L, Long.MAX_VALUE - now()))
                    service.cooldownUntil
                }
                if (retry++ >= 3 || budget.attempts >= 8) throw e
                event(TranslationUpdate.Waiting(until))
            }
        }
    }

    private class ServiceRequests(parallel: Int) {
        val slots = Semaphore(parallel)
        val lock = Mutex()
        var nextStart = 0L
        var cooldownUntil = 0L
    }
    private class RequestBudget {
        var attempts = 0
        var mappingSplit = false
    }
    private fun preserveSpacing(original: String, value: String) = original.takeWhile(Char::isWhitespace) + value.trim() + original.takeLastWhile(Char::isWhitespace)

    // Errors shown in the UI never contain an endpoint, response body or credential.
    private suspend fun <T> result(fallback: TranslationProblem = TranslationProblem.RESPONSE, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) { throw e
    } catch (e: TranslationException) { Result.failure(e)
    } catch (_: Exception) { Result.failure(TranslationException(fallback)) }
}

internal data class TranslationUnit(val text: String, val originals: Map<Int, String>)

/** Display units keep their identity when short units share a request. */
internal fun planTranslationBatches(units: List<TranslationUnit>, provider: TranslationProvider, checkCancellation: () -> Unit = {}, bodyBytes: (List<String>) -> Long): List<List<TranslationUnit>> {
    if (!TranslationClient.supportsBatch(provider)) return units.map { checkCancellation(); listOf(it) }
    val batches = mutableListOf<List<TranslationUnit>>()
    var current = mutableListOf<TranslationUnit>()
    var points = 0
    var bytes = 0
    for (unit in units) {
        checkCancellation()
        val unitPoints = unit.text.codePointCount(0, unit.text.length)
        val unitBytes = unit.text.toByteArray(Charsets.UTF_8).size
        val candidate = current + unit
        if (current.isNotEmpty() && (candidate.size > 30 || points + unitPoints > 4000 || bytes + unitBytes > 12000 || bodyBytes(candidate.map { it.text }) > 20 * 1024)) {
            batches += current; current = mutableListOf(); points = 0; bytes = 0
        }
        if (bodyBytes(listOf(unit.text)) > 20 * 1024) throw TranslationException(TranslationProblem.TOO_LONG)
        current += unit; points += unitPoints; bytes += unitBytes
    }
    if (current.isNotEmpty()) batches += current
    return batches
}
