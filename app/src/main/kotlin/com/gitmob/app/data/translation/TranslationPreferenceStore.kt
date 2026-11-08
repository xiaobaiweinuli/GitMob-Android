package com.gitmob.app.data.translation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gitmob.app.core.auth.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.translationDataStore by preferencesDataStore(name = "translation_settings")

@Singleton
class TranslationPreferenceStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    sharingScope: CoroutineScope,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(context.translationDataStore, json, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val preferencesKey = stringPreferencesKey("preferences")
    private val credentialsKey = stringPreferencesKey("encrypted_credentials")
    private val reloads = MutableStateFlow(0L)
    private val storedConfiguration = dataStore.data.map { data ->
        try {
            TranslationConfiguration(
                preferences = data[preferencesKey]?.let { json.decodeFromString<TranslationPreferences>(it) } ?: TranslationPreferences(),
                credentials = data[credentialsKey]?.let { json.decodeFromString<Map<TranslationProvider, TranslationCredentials>>(TokenCipher.decrypt(it)) } ?: emptyMap(),
            )
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            TranslationConfiguration(storageFailed = true)
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(TranslationConfiguration(storageFailed = true))
    }

    // The singleton Store owns the IO scope and decodes once for all subscribers. Keep the last snapshot
    // across page changes; stop observing storage after the last page has been gone for 5s.
    @OptIn(ExperimentalCoroutinesApi::class)
    val configuration: StateFlow<TranslationConfiguration?> = reloads.flatMapLatest {
        storedConfiguration
    }.stateIn(
        scope = sharingScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = null,
    )

    // Retry a failed read even when another page still holds a subscription in the back stack.
    fun retryConfiguration() { reloads.update { it + 1 } }

    suspend fun setPreferences(value: TranslationPreferences) {
        dataStore.edit { it[preferencesKey] = json.encodeToString(value) }
    }

    suspend fun setCredentials(provider: TranslationProvider, value: TranslationCredentials) {
        dataStore.edit { data ->
            // A decryption failure must never silently overwrite other saved secrets.
            val existing = data[credentialsKey]?.let {
                json.decodeFromString<Map<TranslationProvider, TranslationCredentials>>(TokenCipher.decrypt(it))
            } ?: emptyMap()
            data[credentialsKey] = TokenCipher.encrypt(json.encodeToString(existing + (provider to value)))
        }
    }
}
