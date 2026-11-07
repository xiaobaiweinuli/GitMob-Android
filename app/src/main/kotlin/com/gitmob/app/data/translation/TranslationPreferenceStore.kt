package com.gitmob.app.data.translation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gitmob.app.core.auth.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.translationDataStore by preferencesDataStore(name = "translation_settings")

@Singleton
class TranslationPreferenceStore @Inject constructor(@ApplicationContext private val context: Context, private val json: Json) {
    private val preferencesKey = stringPreferencesKey("preferences")
    private val credentialsKey = stringPreferencesKey("encrypted_credentials")
    val configuration: Flow<TranslationConfiguration> = context.translationDataStore.data.map { data ->
        try {
            TranslationConfiguration(
                preferences = data[preferencesKey]?.let { json.decodeFromString<TranslationPreferences>(it) } ?: TranslationPreferences(),
                credentials = data[credentialsKey]?.let { json.decodeFromString<Map<TranslationProvider, TranslationCredentials>>(TokenCipher.decrypt(it)) } ?: emptyMap(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) {
            TranslationConfiguration(storageFailed = true)
        }
    }.catch { emit(TranslationConfiguration(storageFailed = true)) }.flowOn(Dispatchers.IO)

    suspend fun setPreferences(value: TranslationPreferences) {
        context.translationDataStore.edit { it[preferencesKey] = json.encodeToString(value) }
    }

    suspend fun setCredentials(provider: TranslationProvider, value: TranslationCredentials) {
        context.translationDataStore.edit { data ->
            // A decryption failure must never silently overwrite other saved secrets.
            val existing = data[credentialsKey]?.let {
                json.decodeFromString<Map<TranslationProvider, TranslationCredentials>>(TokenCipher.decrypt(it))
            } ?: emptyMap()
            data[credentialsKey] = TokenCipher.encrypt(json.encodeToString(existing + (provider to value)))
        }
    }
}
