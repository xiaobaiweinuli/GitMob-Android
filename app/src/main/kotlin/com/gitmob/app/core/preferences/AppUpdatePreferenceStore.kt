package com.gitmob.app.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gitmob.app.data.model.AppUpdatePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appUpdateDataStore by preferencesDataStore("app_update_preferences")

/** 应用更新偏好与账号无关；只有 DataStore 的真实值才能触发自动检查。 */
@Singleton
class AppUpdatePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val automaticKey = booleanPreferencesKey("automatic")
    private val ignoredVersionKey = intPreferencesKey("ignored_version_code")
    private val lastCheckKey = longPreferencesKey("last_successful_check_at")

    val preferences = context.appUpdateDataStore.data.map { it.toModel() }

    suspend fun setAutomatic(enabled: Boolean): AppUpdatePreferences =
        context.appUpdateDataStore.edit { it[automaticKey] = enabled }.toModel()

    suspend fun ignore(versionCode: Int): AppUpdatePreferences =
        context.appUpdateDataStore.edit { it[ignoredVersionKey] = versionCode }.toModel()

    suspend fun recordSuccessfulCheck(timestamp: Long) {
        context.appUpdateDataStore.edit { it[lastCheckKey] = timestamp }
    }

    private fun Preferences.toModel() = AppUpdatePreferences(
        automatic = this[automaticKey] ?: true,
        ignoredVersionCode = this[ignoredVersionKey],
        lastSuccessfulCheckAt = this[lastCheckKey] ?: 0,
    )
}
