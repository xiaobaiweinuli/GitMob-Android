package com.gitmob.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EncryptedSharedPreferences 封装类（单例模式）
 *
 * 功能：
 * - 所有数据自动加密存储
 * - 提供 StateFlow API（兼容原来的 DataStore 用法）
 * - Master Key 自动存储在 Keystore
 * - 单例模式，只初始化一次
 *
 * 使用方式：
 * ```kotlin
 * val prefs = EncryptedPreferences.getInstance(context)
 * prefs.putString("key", "value")
 * val value = prefs.getString("key")
 *
 * // Flow API（兼容 DataStore）
 * val flow = prefs.getStringFlow("key")
 * ```
 */
class EncryptedPreferences private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "gitmob_encrypted_prefs"
        private const val TAG = "EncryptedPrefs"

        @Volatile
        private var instance: EncryptedPreferences? = null

        fun getInstance(context: Context): EncryptedPreferences {
            return instance ?: synchronized(this) {
                instance ?: EncryptedPreferences(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences

    // 用于缓存 StateFlow（按 key 缓存）
    private val stringFlows = mutableMapOf<String, MutableStateFlow<String?>>()
    private val intFlows = mutableMapOf<String, MutableStateFlow<Int>>()
    private val booleanFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // 监听变化，更新所有 Flow
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            LogManager.v(TAG, "Key changed: $key")
            if (key != null) {
                updateFlowsForKey(key)
            }
        }

        LogManager.i(TAG, "EncryptedPreferences 初始化成功")
    }

    // ── 基础存取方法 ──────────────────────────────────────────────

    fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return prefs.getString(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    // ── Flow API（兼容 DataStore）──────────────────────────────────

    fun getStringFlow(key: String, defaultValue: String? = null): StateFlow<String?> {
        return stringFlows.getOrPut(key) {
            MutableStateFlow(getString(key, defaultValue))
        }.asStateFlow()
    }

    fun getIntFlow(key: String, defaultValue: Int = 0): StateFlow<Int> {
        return intFlows.getOrPut(key) {
            MutableStateFlow(getInt(key, defaultValue))
        }.asStateFlow()
    }

    fun getBooleanFlow(key: String, defaultValue: Boolean = false): StateFlow<Boolean> {
        return booleanFlows.getOrPut(key) {
            MutableStateFlow(getBoolean(key, defaultValue))
        }.asStateFlow()
    }

    // ── 内部方法 ─────────────────────────────────────────────────

    private fun updateFlowsForKey(key: String) {
        stringFlows[key]?.value = getString(key)
        intFlows[key]?.value = getInt(key, 0)
        booleanFlows[key]?.value = getBoolean(key, false)
    }
}
