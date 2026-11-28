package com.gitmob.app.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gitmob.app.testutil.NetworkPreferencesFixture
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RawAccelerationPreferenceStoreTest {
    @Test fun startsEmptyAndPersistsSelectionWithAtomicRevisions() = runTest {
        val data = NetworkPreferencesFixture()
        val store = RawAccelerationPreferenceStore(data)
        assertNull(store.settings.value)
        assertEquals(RawAccelerationSettings(), store.initialize())
        val service = RawAccelerationService("a", "One", "https://proxy.example/")
        store.update { it.copy(services = listOf(service), selectedId = "a", enabled = true) }
        (1..10).map { async { store.update { it.copy(enabled = !it.enabled) } } }.awaitAll()
        val restored = RawAccelerationPreferenceStore(data).initialize()
        assertEquals(store.settings.value, restored)
        assertEquals(11L, restored.revision)
        assertEquals(service.prefix, restored.activePrefix)
        store.update { it }
        assertEquals(11L, store.settings.value?.revision)
    }

    @Test fun callerTimeoutRemainsCancellationAndAllowsRetry() = runTest {
        var pending = true
        val data = mockk<DataStore<Preferences>>()
        every { data.data } returns flow {
            if (pending) awaitCancellation()
            emit(preferencesOf())
        }
        val store = RawAccelerationPreferenceStore(data)
        val failure = runCatching { withTimeout(500) { store.initialize() } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertNull(store.settings.value)
        pending = false
        assertEquals(RawAccelerationSettings(), store.initialize())
    }

    @Test fun settingsReadTimeoutReportsFailureWithoutPublishingDefaults() = runTest {
        val data = mockk<DataStore<Preferences>>()
        every { data.data } returns flow { awaitCancellation() }
        val store = RawAccelerationPreferenceStore(data)
        assertTrue(runCatching { store.initialize() }.exceptionOrNull() is IOException)
        assertNull(store.settings.value)
    }

    @Test fun failedAndCorruptReadsNeverPublishDefaultsOrOverwriteData() = runTest {
        val failed = mockk<DataStore<Preferences>>()
        every { failed.data } returns flow { throw IOException("read failed") }
        val store = RawAccelerationPreferenceStore(failed)
        assertTrue(runCatching { store.initialize() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { store.update { RawAccelerationSettings() } }.exceptionOrNull() is IOException)
        assertNull(store.settings.value)
        val data = NetworkPreferencesFixture(preferencesOf(stringPreferencesKey("settings") to "broken json"))
        val corrupt = RawAccelerationPreferenceStore(data)
        assertTrue(runCatching { corrupt.initialize() }.exceptionOrNull() is IOException)
        assertNull(corrupt.settings.value)
        assertEquals("broken json", data.data.value[stringPreferencesKey("settings")])
    }
}
