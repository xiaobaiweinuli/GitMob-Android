package com.gitmob.app.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RawAccelerationService(val id: String, val name: String, val prefix: String)

@Serializable
data class RawAccelerationSettings(
    val services: List<RawAccelerationService> = emptyList(),
    val selectedId: String? = null,
    val enabled: Boolean = false,
    val revision: Long = 0,
) {
    val activePrefix: String? get() = if (enabled) services.find { it.id == selectedId }?.prefix else null
}

private val Context.rawAccelerationDataStore by preferencesDataStore("raw_acceleration")

@Singleton
class RawAccelerationPreferenceStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.rawAccelerationDataStore)

    private val mutex = Mutex()
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true }
    private val snapshot = MutableStateFlow<RawAccelerationSettings?>(null)
    val settings = snapshot.asStateFlow()

    /** Failed reads never publish defaults or overwrite the saved selection. */
    suspend fun initialize(): RawAccelerationSettings = mutex.withLock { load() }

    suspend fun update(transform: (RawAccelerationSettings) -> RawAccelerationSettings): RawAccelerationSettings = mutex.withLock {
        val current = load()
        val next = transform(current)
        if (next == current) return@withLock current
        val saved = next.copy(revision = current.revision + 1)
        dataStore.edit { it[key] = json.encodeToString(saved) }
        snapshot.value = saved
        saved
    }

    private suspend fun load(): RawAccelerationSettings {
        snapshot.value?.let { return it }
        val preferences = try { withTimeout(5_000) { dataStore.data.first() } }
            catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("Network preferences read timed out")
            }
        val loaded = try {
            preferences[key]?.let { json.decodeFromString<RawAccelerationSettings>(it) } ?: RawAccelerationSettings()
        } catch (error: SerializationException) {
            throw IOException("Invalid network preferences", error)
        }
        snapshot.value = loaded
        return loaded
    }
}
