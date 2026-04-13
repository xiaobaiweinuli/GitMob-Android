package com.gitmob.android.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gitmob_prefs")

/** 主题模式：0=浅色(默认) 1=深色 2=跟随系统 */
enum class ThemeMode(val value: Int) {
    LIGHT(0), DARK(1), SYSTEM(2);
    companion object {
        fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: LIGHT
    }
}

class TokenStorage(private val context: Context) {

    private object Keys {
        val ACCESS_TOKEN  = stringPreferencesKey("access_token")
        val USER_LOGIN    = stringPreferencesKey("user_login")
        val USER_NAME     = stringPreferencesKey("user_name")
        val USER_EMAIL    = stringPreferencesKey("user_email")
        val AVATAR_URL    = stringPreferencesKey("avatar_url")
        val THEME_MODE    = intPreferencesKey("theme_mode")
        val ROOT_ENABLED  = booleanPreferencesKey("root_enabled")
        val LOCAL_REPOS   = stringPreferencesKey("local_repos_json")   // JSON 列表
        val BOOKMARKS     = stringPreferencesKey("file_bookmarks_json") // 自定义书签
        val LOG_LEVEL     = intPreferencesKey("log_level")
        val TAB_STEP_BACK = booleanPreferencesKey("tab_step_back")      // 仓库详情Tab逐级返回
        val SU_EXEC_MODE  = intPreferencesKey("su_exec_mode")            // 已探测的 su 执行模式缓存
        val SEARCH_HISTORY = stringPreferencesKey("search_history_json") // 全局搜索历史（JSON数组）
    }

    // ── 收藏夹（按账号隔离：key = "favorites_json_{login}"）──────────────────
    fun favoritesJsonFlow(login: String): Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("favorites_json_$login")] ?: "{\"groups\":[],\"ungrouped\":[]}"
    }

    suspend fun saveFavoritesJson(login: String, json: String) {
        context.dataStore.edit { it[stringPreferencesKey("favorites_json_$login")] = json }
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { it[Keys.ACCESS_TOKEN] }
    val userLogin:   Flow<String?> = context.dataStore.data.map { it[Keys.USER_LOGIN] }

    val userProfile: Flow<Triple<String, String, String>?> = context.dataStore.data.map { prefs ->
        val login = prefs[Keys.USER_LOGIN] ?: return@map null
        val name = prefs[Keys.USER_NAME] ?: login
        val email = prefs[Keys.USER_EMAIL] ?: "$login@users.noreply.github.com"
        Triple(login, email, prefs[Keys.AVATAR_URL] ?: "")
    }

    /** 默认跟随系统（SYSTEM=2） */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        ThemeMode.fromInt(it[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.value)
    }

    val rootEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ROOT_ENABLED] ?: false
    }

    val localReposJson: Flow<String> = context.dataStore.data.map {
        it[Keys.LOCAL_REPOS] ?: "[]"
    }

    val logLevel: Flow<Int> = context.dataStore.data.map { it[Keys.LOG_LEVEL] ?: 1 } // 默认 DEBUG=1

    val bookmarksJson: Flow<String> = context.dataStore.data.map {
        it[Keys.BOOKMARKS] ?: "[]"
    }

    /** 仓库详情Tab逐级返回开关，默认关闭 */
    val tabStepBack: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.TAB_STEP_BACK] ?: false
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { it[Keys.ACCESS_TOKEN] = token }
    }
    suspend fun saveUser(login: String, name: String?, email: String?, avatarUrl: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.USER_LOGIN] = login
            prefs[Keys.USER_NAME]  = name ?: login
            prefs[Keys.USER_EMAIL] = email ?: "$login@users.noreply.github.com"
            prefs[Keys.AVATAR_URL] = avatarUrl ?: ""
        }
    }
    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.value }
    }
    suspend fun setRootEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ROOT_ENABLED] = enabled }
    }

    suspend fun saveLocalReposJson(json: String) {
        context.dataStore.edit { it[Keys.LOCAL_REPOS] = json }
    }

    suspend fun setLogLevel(level: Int) {
        context.dataStore.edit { it[Keys.LOG_LEVEL] = level }
    }

    suspend fun saveBookmarksJson(json: String) {
        context.dataStore.edit { it[Keys.BOOKMARKS] = json }
    }

    suspend fun setTabStepBack(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TAB_STEP_BACK] = enabled }
    }

    /** su 执行模式缓存：-1=未知，0=NSENTER，1=MOUNT_MASTER，2=PLAIN */
    suspend fun getSuExecModeCache(): Int =
        context.dataStore.data.map { it[Keys.SU_EXEC_MODE] ?: -1 }.first()

    suspend fun setSuExecModeCache(mode: Int) {
        context.dataStore.edit { it[Keys.SU_EXEC_MODE] = mode }
    }

    // ── 全局搜索历史（最多保留 15 条，JSON 数组格式）──────────────────────────
    val searchHistoryJson: Flow<String> = context.dataStore.data.map {
        it[Keys.SEARCH_HISTORY] ?: "[]"
    }

    suspend fun addSearchHistory(query: String) {
        if (query.isBlank()) return
        context.dataStore.edit { prefs ->
            val raw = prefs[Keys.SEARCH_HISTORY] ?: "[]"
            // 简单 JSON 数组解析（避免引入额外解析库）
            val list = raw.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() && it != query }
                .toMutableList()
            list.add(0, query)
            val trimmed = list.take(15)
            prefs[Keys.SEARCH_HISTORY] = "[" + trimmed.joinToString(",") { "\"$it\"" } + "]"
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { it[Keys.SEARCH_HISTORY] = "[]" }
    }

    suspend fun clear() { context.dataStore.edit { it.clear() } }

    // ── 多账号支持 ──────────────────────────────────────────────
    suspend fun syncActiveAccount(info: AccountInfo) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = info.token
            prefs[Keys.USER_LOGIN]   = info.login
            prefs[Keys.USER_NAME]    = info.name
            prefs[Keys.USER_EMAIL]   = info.email
            prefs[Keys.AVATAR_URL]   = info.avatarUrl
        }
    }

    suspend fun clearActiveAccount() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.USER_LOGIN)
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.AVATAR_URL)
        }
    }
}
