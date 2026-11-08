package com.gitmob.app.data.translation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gitmob.app.core.auth.TokenCipher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationPreferenceStoreTest {
    private val preferencesKey = stringPreferencesKey("preferences")
    private val credentialsKey = stringPreferencesKey("encrypted_credentials")
    private val enabled = TranslationPreferences(enabled = true)

    @After fun tearDown() { unmockkObject(TokenCipher) }

    private fun TestScope.store(data: Flow<Preferences>): TranslationPreferenceStore {
        val dataStore = mockk<DataStore<Preferences>>()
        every { dataStore.data } returns data
        return TranslationPreferenceStore(dataStore, Json, backgroundScope)
    }

    private fun TestScope.subscribe(store: TranslationPreferenceStore) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.configuration.collect() }

    @Test fun noSubscriberLeavesConfigurationUnloadedAndEmptyStorageEventuallyMeansOff() = runTest {
        var reads = 0
        val store = store(flow { reads++; emit(emptyPreferences()) })
        runCurrent()
        assertNull(store.configuration.value)
        assertEquals(0, reads)
        subscribe(store)
        runCurrent()
        assertEquals(TranslationConfiguration(), store.configuration.value)
        assertEquals(1, reads)
    }

    @Test fun multiplePagesShareOneStorageCollectionAndOneCredentialDecodePerUpdate() = runTest {
        mockkObject(TokenCipher)
        val credentials = mapOf(TranslationProvider.BAIDU to TranslationCredentials(appId = "test", secret = "test-secret"))
        every { TokenCipher.decrypt("ciphertext") } returns Json.encodeToString(credentials)
        val data = MutableStateFlow(preferencesOf(preferencesKey to Json.encodeToString(enabled), credentialsKey to "ciphertext"))
        var reads = 0
        val store = store(data.onStart { reads++ })
        val first = subscribe(store)
        subscribe(store)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(credentials, store.configuration.value?.credentials)
        verify(exactly = 1) { TokenCipher.decrypt("ciphertext") }

        first.cancel()
        runCurrent()
        data.value = preferencesOf(preferencesKey to Json.encodeToString(enabled.copy(target = "ja")), credentialsKey to "ciphertext")
        runCurrent()
        assertEquals("ja", store.configuration.value?.preferences?.target)
        assertEquals(1, reads)
        verify(exactly = 2) { TokenCipher.decrypt("ciphertext") }
    }

    @Test fun quickReentryReusesSubscriptionAndIdleReentryKeepsSnapshotUntilRefresh() = runTest {
        val data = MutableStateFlow(preferencesOf(preferencesKey to Json.encodeToString(enabled)))
        var starts = 0
        var stops = 0
        val store = store(data.onStart { starts++ }.onCompletion { stops++ })
        val first = subscribe(store)
        runCurrent()
        first.cancel()
        runCurrent()
        advanceTimeBy(4_999)
        val second = subscribe(store)
        runCurrent()
        assertEquals(1, starts)
        assertEquals(0, stops)
        second.cancel()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, stops)
        assertEquals(enabled, store.configuration.value?.preferences)

        val updated = enabled.copy(provider = TranslationProvider.TRANSMART)
        data.value = preferencesOf(preferencesKey to Json.encodeToString(updated))
        runCurrent()
        assertEquals(enabled, store.configuration.value?.preferences)
        subscribe(store)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(updated, store.configuration.value?.preferences)
    }

    @Test fun malformedSettingsAreAnErrorAndNextValidEmissionRecovers() = runTest {
        val data = MutableStateFlow(preferencesOf(preferencesKey to "not-json"))
        val store = store(data)
        subscribe(store)
        runCurrent()
        assertEquals(true, store.configuration.value?.storageFailed)
        data.value = preferencesOf(preferencesKey to Json.encodeToString(enabled))
        runCurrent()
        assertEquals(TranslationConfiguration(enabled), store.configuration.value)
    }

    @Test fun decryptionFailureDoesNotExposeAnEnabledConfigurationOrCredentials() = runTest {
        mockkObject(TokenCipher)
        every { TokenCipher.decrypt("broken") } throws IllegalStateException("Cannot decrypt")
        val store = store(flow { emit(preferencesOf(preferencesKey to Json.encodeToString(enabled), credentialsKey to "broken")) })
        subscribe(store)
        runCurrent()
        val configuration = requireNotNull(store.configuration.value)
        assertTrue(configuration.storageFailed)
        assertFalse(configuration.preferences.enabled)
        assertTrue(configuration.credentials.isEmpty())
    }

    @Test fun storageReadFailureIsAnErrorAndReopeningAfterIdleCanRecover() = runTest {
        var fail = true
        val store = store(flow {
            if (fail) throw IOException("Cannot read settings")
            emit(preferencesOf(preferencesKey to Json.encodeToString(enabled)))
        })
        val first = subscribe(store)
        runCurrent()
        assertEquals(true, store.configuration.value?.storageFailed)
        first.cancel()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        fail = false
        subscribe(store)
        runCurrent()
        assertEquals(TranslationConfiguration(enabled), store.configuration.value)
    }

    @Test fun retryRecoversAReadFailureWhileExistingPagesStaySubscribed() = runTest {
        var reads = 0
        val store = store(flow {
            reads++
            if (reads == 1) throw IOException("Temporary read failure")
            emit(preferencesOf(preferencesKey to Json.encodeToString(enabled)))
        })
        subscribe(store)
        subscribe(store)
        runCurrent()
        assertEquals(true, store.configuration.value?.storageFailed)
        store.retryConfiguration()
        runCurrent()
        assertEquals(2, reads)
        assertEquals(TranslationConfiguration(enabled), store.configuration.value)
    }

    @Test fun cancellationIsNotPublishedAsAStorageError() = runTest {
        val store = store(flow { throw CancellationException("Scope cancelled") })
        subscribe(store)
        runCurrent()
        assertNull(store.configuration.value)
    }
}
