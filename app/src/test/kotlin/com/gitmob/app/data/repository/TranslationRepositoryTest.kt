package com.gitmob.app.data.repository

import com.gitmob.app.data.translation.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationRepositoryTest {
    private val client = mockk<TranslationClient>()
    private val store = mockk<TranslationPreferenceStore>()
    private val preferences = TranslationPreferences(enabled = true)
    private val credentials = TranslationCredentials()
    private fun TestScope.repository(): TranslationRepository {
        every { store.configuration } returns MutableStateFlow(TranslationConfiguration(preferences))
        every { client.requestBytes(any(), any(), any()) } answers { firstArg<List<String>>().sumOf { it.toByteArray().size.toLong() } + 100 }
        return TranslationRepository(store, client, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
    }
    private fun document(parts: List<String>) = TranslationDocument(TranslationTarget("body", parts.joinToString("")), parts)

    @Test fun fortyFiveUnitsUseTwoRequestsAndFirstBatchArrivesBeforeSecondFinishes() = runTest {
        val repository = repository()
        val starts = mutableListOf<Long>()
        coEvery { client.translateBatch(any(), any(), any()) } coAnswers {
            starts += testScheduler.currentTime
            val texts = firstArg<List<String>>()
            delay(if (starts.size == 1) 100 else 5000)
            texts.map { "译文$it" }
        }
        val events = mutableListOf<TranslationUpdate>()
        val task = launch { repository.translateDocument(document(List(45) { "paragraph-$it" }), emptyMap(), preferences, credentials).toList(events) }
        advanceTimeBy(101); runCurrent()
        assertEquals(30, events.filterIsInstance<TranslationUpdate.Batch>().sumOf { it.values.size })
        assertTrue(task.isActive)
        advanceUntilIdle()
        assertEquals(listOf(0L, 300L), starts)
        assertEquals(45, events.filterIsInstance<TranslationUpdate.Batch>().sumOf { it.values.size })
        coVerify(exactly = 2) { client.translateBatch(any(), any(), any()) }
    }

    @Test fun repeatedTextSharesARequestAndRetainsEachOriginalWhitespace() = runTest {
        val repository = repository()
        coEvery { client.translateBatch(any(), any(), any()) } returns listOf("你好")
        val events = repository.translateDocument(document(listOf("Hello ", " Hello", "Hello")), emptyMap(), preferences, credentials).toList()
        assertEquals(mapOf(0 to "你好 ", 1 to " 你好", 2 to "你好"), events.filterIsInstance<TranslationUpdate.Batch>().single().values)
        coVerify(exactly = 1) { client.translateBatch(listOf("Hello"), preferences, credentials) }
    }

    @Test fun retryReusesCompletedDuplicateWithoutAnotherRequest() = runTest {
        val events = repository().translateDocument(document(listOf("Hello ", "Hello")), mapOf(0 to "你好 "), preferences, credentials).toList()
        assertEquals(mapOf(1 to "你好"), events.filterIsInstance<TranslationUpdate.Batch>().single().values)
        coVerify(exactly = 0) { client.translateBatch(any(), any(), any()) }
    }

    @Test fun rateLimitRetriesTheSameBatchAfterCooldownWithoutSplitting() = runTest {
        val repository = repository()
        var attempt = 0
        val starts = mutableListOf<Long>()
        coEvery { client.translateBatch(any(), any(), any()) } coAnswers {
            starts += testScheduler.currentTime
            if (attempt++ == 0) throw TranslationException(TranslationProblem.LIMIT, httpStatus = 429, retryAfterMillis = 4000)
            firstArg<List<String>>().map { "译文" }
        }
        val events = repository.translateDocument(document(listOf("one", "two", "three")), emptyMap(), preferences, credentials).toList()
        assertEquals(listOf(0L, 4000L), starts)
        assertTrue(events.any { it is TranslationUpdate.Waiting && it.untilMillis == 4000L })
        coVerify(exactly = 2) { client.translateBatch(listOf("one", "two", "three"), preferences, credentials) }
    }

    @Test fun rateRetriesHaveABoundAndQuotaErrorsDoNotRetry() = runTest {
        val repository = repository()
        coEvery { client.translateBatch(any(), any(), any()) } throws TranslationException(TranslationProblem.LIMIT)
        val error = runCatching { repository.translateDocument(document(listOf("one")), emptyMap(), preferences, credentials).toList() }.exceptionOrNull()
        assertEquals(TranslationProblem.LIMIT, (error as TranslationException).problem)
        assertEquals(21000L, testScheduler.currentTime)
        coVerify(exactly = 4) { client.translateBatch(any(), any(), any()) }
        coEvery { client.translateBatch(any(), any(), any()) } throws TranslationException(TranslationProblem.QUOTA)
        repository.translate("other", preferences.copy(provider = TranslationProvider.BAIDU), credentials)
        coVerify(exactly = 5) { client.translateBatch(any(), any(), any()) }
    }

    @Test fun coolingServiceDoesNotBlockExplicitlySelectedOtherService() = runTest {
        val repository = repository()
        coEvery { client.translateBatch(any(), match { it.provider == TranslationProvider.EDGE }, any()) } throws TranslationException(TranslationProblem.LIMIT, retryAfterMillis = 60000)
        coEvery { client.translateBatch(any(), match { it.provider == TranslationProvider.TRANSMART }, any()) } returns listOf("其他服务")
        val task = launch { repository.translate("one", preferences, credentials) }
        runCurrent()
        val result = repository.translate("two", preferences.copy(provider = TranslationProvider.TRANSMART), credentials)
        assertEquals("其他服务", result.getOrThrow())
        assertEquals(0L, testScheduler.currentTime)
        task.cancelAndJoin()
    }

    @Test fun mappingMismatchSplitsOnceAndNeverAssignsWrongResponseByIndex() = runTest {
        val repository = repository()
        coEvery { client.translateBatch(any(), any(), any()) } coAnswers {
            val texts = firstArg<List<String>>()
            if (texts.size == 2) listOf("wrong") else texts.map { "translated-$it" }
        }
        val events = repository.translateDocument(document(listOf("one", "two")), emptyMap(), preferences, credentials).toList()
        assertEquals(mapOf(0 to "translated-one", 1 to "translated-two"), events.filterIsInstance<TranslationUpdate.Batch>().flatMap { it.values.entries }.associate { it.toPair() })
        coVerify(exactly = 3) { client.translateBatch(any(), any(), any()) }
    }

    @Test fun cancellationStopsQueuedRequestsAndReleasesCapacity() = runTest {
        val repository = repository()
        var cancelled = 0
        coEvery { client.translateBatch(any(), any(), any()) } coAnswers { try { awaitCancellation() } finally { cancelled++ } }
        val task = launch { repository.translateDocument(document(List(80) { "unit-$it" }), emptyMap(), preferences, credentials).collect() }
        advanceTimeBy(500); runCurrent()
        task.cancelAndJoin()
        assertEquals(2, cancelled)
        coEvery { client.translateBatch(any(), any(), any()) } returns listOf("new")
        assertEquals("new", repository.translate("replacement", preferences, credentials).getOrThrow())
        coVerify(exactly = 3) { client.translateBatch(any(), any(), any()) }
    }

    @Test fun plannerHonorsCodepointsBytesAndSerializedBodyBudget() {
        val units = List(45) { TranslationUnit("paragraph-$it", mapOf(it to "paragraph-$it")) }
        assertEquals(listOf(30, 15), planTranslationBatches(units, TranslationProvider.EDGE) { 100 }.map { it.size })
        assertEquals(listOf(10, 10, 10, 10, 5), planTranslationBatches(units, TranslationProvider.EDGE) { it.size * 2000L }.map { it.size })
        val long = List(3) { TranslationUnit("😀".repeat(1500), mapOf(it to "original")) }
        assertEquals(listOf(2, 1), planTranslationBatches(long, TranslationProvider.EDGE) { 100 }.map { it.size })
    }

    @Test fun batchPlanningRunsOnCpuDispatcherWhileNetworkKeepsCallerContext() = runTest {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "translation-cpu-test") }
        executor.asCoroutineDispatcher().use { cpu ->
            every { store.configuration } returns MutableStateFlow(TranslationConfiguration(preferences))
            val planningThreads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
            every { client.requestBytes(any(), any(), any()) } answers {
                planningThreads += Thread.currentThread()
                100L
            }
            var networkThread: Thread? = null
            coEvery { client.translateBatch(any(), any(), any()) } coAnswers {
                networkThread = Thread.currentThread()
                firstArg<List<String>>().map { "translated-$it" }
            }
            val callerThread = Thread.currentThread()
            val repository = TranslationRepository(store, client, cpu) { testScheduler.currentTime }
            val events = repository.translateDocument(document(listOf("one", "two", "one")), emptyMap(), preferences, credentials).toList()
            assertTrue(planningThreads.isNotEmpty())
            assertTrue(planningThreads.all { it.name.startsWith("translation-cpu-test") })
            assertSame(callerThread, networkThread)
            assertEquals(3, events.filterIsInstance<TranslationUpdate.Batch>().sumOf { it.values.size })
        }
    }

    @Test fun cancellationDuringGroupingStopsScanningAndNeverStartsNetwork() = runTest {
        val repository = repository()
        val cancelled = Job(coroutineContext[Job])
        var visited = 0
        val parts = object : AbstractList<String>() {
            override val size = 100_000
            override fun get(index: Int): String {
                visited++
                if (visited == 128) cancelled.cancel()
                return "paragraph-$index"
            }
        }
        val document = TranslationDocument(TranslationTarget("large", ""), parts)
        val error = runCatching {
            withContext(cancelled) { repository.translateDocument(document, emptyMap(), preferences, credentials).toList() }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(visited < 200)
        coVerify(exactly = 0) { client.translateBatch(any(), any(), any()) }
    }

    @Test fun cancellationDuringBatchSizingStopsBeforeNetwork() = runTest {
        val repository = repository()
        val cancelled = Job(coroutineContext[Job])
        var sized = 0
        every { client.requestBytes(any(), any(), any()) } answers {
            sized++
            if (sized == 3) cancelled.cancel()
            100L
        }
        val error = runCatching {
            withContext(cancelled) { repository.translateDocument(document(List(1000) { "paragraph-$it" }), emptyMap(), preferences, credentials).toList() }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(sized < 10)
        coVerify(exactly = 0) { client.translateBatch(any(), any(), any()) }
    }

    @Test fun tooLongUnitSplitsWithoutChangingOriginalIdsOrWhitespace() = runTest {
        val repository = repository()
        var attempts = 0
        val original = "abcdefghij".repeat(10)
        coEvery { client.translateBatch(any(), any(), any()) } coAnswers {
            if (attempts++ == 0) throw TranslationException(TranslationProblem.TOO_LONG)
            firstArg<List<String>>().map { it.uppercase() }
        }
        val events = repository.translateDocument(document(listOf(" $original", "$original ")), emptyMap(), preferences, credentials).toList()
        val batches = events.filterIsInstance<TranslationUpdate.Batch>()
        assertEquals(1, batches.size)
        assertEquals(mapOf(0 to " "+original.uppercase(), 1 to original.uppercase()+" "), batches.single().values)
        assertEquals(3, attempts)
    }
}
