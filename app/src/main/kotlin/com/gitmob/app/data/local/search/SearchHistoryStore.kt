package com.gitmob.app.data.local.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.PageSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchHistoryDataStore by preferencesDataStore("search_history")

@Singleton
class SearchHistoryStore internal constructor(
    private val store: DataStore<Preferences>, private val json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) : this(context.searchHistoryDataStore, json)

    fun observe(epoch: AccountEpoch): Flow<List<String>> = store.data.map { preferences ->
        epoch.ensureCurrent()
        decode(preferences[key(epoch)])
    }

    suspend fun record(epoch: AccountEpoch, query: String) = safeCall {
        val normalized = query.trim()
        if (normalized.isNotEmpty()) store.edit { preferences ->
            epoch.ensureCurrent()
            val key = key(epoch)
            preferences[key] = json.encodeToString(updatedHistory(decode(preferences[key]), normalized))
        }
        Unit
    }

    suspend fun clear(epoch: AccountEpoch) = safeCall {
        store.edit { preferences -> epoch.ensureCurrent(); preferences.remove(key(epoch)) }
        Unit
    }

    private fun key(epoch: AccountEpoch) = stringPreferencesKey("queries:" + requireNotNull(epoch.accountKey))
    private fun decode(value: String?): List<String> = value?.let { json.decodeFromString<List<String>>(it) }.orEmpty()
}

internal fun updatedHistory(current: List<String>, query: String): List<String> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return current
    return (listOf(normalized) + current.filterNot { it == normalized }).take(PageSize.SEARCH_HISTORY)
}
