package com.gitmob.android.auth

import android.content.Context
import com.gitmob.android.util.EncryptedPreferences
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 主题模式：0=浅色(默认) 1=深色 2=跟随系统 */
enum class ThemeMode(val value: Int) {
    LIGHT(0), DARK(1), SYSTEM(2);
    companion object {
        fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: LIGHT
    }
}

class TokenStorage(private val context: Context) {

    private val prefs = EncryptedPreferences.getInstance(context)

    private object Keys {
        const val ACCESS_TOKEN  = "access_token"
        const val USER_LOGIN    = "user_login"
        const val USER_NAME     = "user_name"
        const val USER_EMAIL    = "user_email"
        const val AVATAR_URL    = "avatar_url"
        const val THEME_MODE    = "theme_mode"
        const val ROOT_ENABLED  = "root_enabled"
        const val LOCAL_REPOS   = "local_repos_json"
        const val BOOKMARKS     = "file_bookmarks_json"
        const val LOG_LEVEL     = "log_level"
        const val TAB_STEP_BACK = "tab_step_back"
        const val SU_EXEC_MODE  = "su_exec_mode"
        const val SEARCH_HISTORY = "search_history_json"
    }

    // ── 收藏夹（按账号隔离：key = "favorites_json_{login}"）──────────────────
    fun favoritesJsonFlow(login: String): Flow<String> = prefs.getStringFlow(
        "favorites_json_$login",
        "{\"groups\":[],\"ungrouped\":[]}"
    ).map { it!! }

    suspend fun saveFavoritesJson(login: String, json: String) {
        prefs.putString("favorites_json_$login", json)
    }

    val accessToken: Flow<String?> = prefs.getStringFlow(Keys.ACCESS_TOKEN)
    val userLogin:   Flow<String?> = prefs.getStringFlow(Keys.USER_LOGIN)

    val userProfile: Flow<Triple<String, String, String>?> = prefs.getStringFlow(Keys.USER_LOGIN).map { login ->
        if (login == null) return@map null
        val name = prefs.getString(Keys.USER_NAME) ?: login
        val email = prefs.getString(Keys.USER_EMAIL) ?: "$login@users.noreply.github.com"
        Triple(login, email, prefs.getString(Keys.AVATAR_URL) ?: "")
    }

    /** 默认跟随系统（SYSTEM=2） */
    val themeMode: Flow<ThemeMode> = prefs.getIntFlow(Keys.THEME_MODE, ThemeMode.SYSTEM.value)
        .map { ThemeMode.fromInt(it) }

    val rootEnabled: Flow<Boolean> = prefs.getBooleanFlow(Keys.ROOT_ENABLED, false)

    val localReposJson: Flow<String> = prefs.getStringFlow(Keys.LOCAL_REPOS, "[]").map { it!! }

    val logLevel: Flow<Int> = prefs.getIntFlow(Keys.LOG_LEVEL, 1) // 默认 DEBUG=1

    val bookmarksJson: Flow<String> = prefs.getStringFlow(Keys.BOOKMARKS, "[]").map { it!! }

    /** 仓库详情Tab逐级返回开关，默认关闭 */
    val tabStepBack: Flow<Boolean> = prefs.getBooleanFlow(Keys.TAB_STEP_BACK, false)

    suspend fun saveToken(token: String) {
        prefs.putString(Keys.ACCESS_TOKEN, token)
    }

    suspend fun saveUser(login: String, name: String?, email: String?, avatarUrl: String?) {
        prefs.putString(Keys.USER_LOGIN, login)
        prefs.putString(Keys.USER_NAME, name ?: login)
        prefs.putString(Keys.USER_EMAIL, email ?: "$login@users.noreply.github.com")
        prefs.putString(Keys.AVATAR_URL, avatarUrl ?: "")
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        prefs.putInt(Keys.THEME_MODE, mode.value)
    }

    suspend fun setRootEnabled(enabled: Boolean) {
        prefs.putBoolean(Keys.ROOT_ENABLED, enabled)
    }

    suspend fun saveLocalReposJson(json: String) {
        prefs.putString(Keys.LOCAL_REPOS, json)
    }

    suspend fun setLogLevel(level: Int) {
        prefs.putInt(Keys.LOG_LEVEL, level)
    }

    suspend fun saveBookmarksJson(json: String) {
        prefs.putString(Keys.BOOKMARKS, json)
    }

    suspend fun setTabStepBack(enabled: Boolean) {
        prefs.putBoolean(Keys.TAB_STEP_BACK, enabled)
    }

    /** su 执行模式缓存：-1=未知，0=NSENTER，1=MOUNT_MASTER，2=PLAIN */
    suspend fun getSuExecModeCache(): Int = prefs.getInt(Keys.SU_EXEC_MODE, -1)

    suspend fun setSuExecModeCache(mode: Int) {
        prefs.putInt(Keys.SU_EXEC_MODE, mode)
    }

    // ── 全局搜索历史（最多保留 15 条，JSON 数组格式）──────────────────────────
    val searchHistoryJson: Flow<String> = prefs.getStringFlow(Keys.SEARCH_HISTORY, "[]").map { it!! }

    suspend fun addSearchHistory(query: String) {
        if (query.isBlank()) return
        val raw = prefs.getString(Keys.SEARCH_HISTORY) ?: "[]"
        val list = raw.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() && it != query }
            .toMutableList()
        list.add(0, query)
        val trimmed = list.take(15)
        prefs.putString(Keys.SEARCH_HISTORY, "[" + trimmed.joinToString(",") { "\"$it\"" } + "]")
    }

    suspend fun clearSearchHistory() {
        prefs.putString(Keys.SEARCH_HISTORY, "[]")
    }

    suspend fun clear() {
        prefs.clear()
    }

    // ── 多账号支持 ──────────────────────────────────────────────
    suspend fun syncActiveAccount(info: AccountInfo) {
        prefs.putString(Keys.ACCESS_TOKEN, info.token)
        prefs.putString(Keys.USER_LOGIN, info.login)
        prefs.putString(Keys.USER_NAME, info.name)
        prefs.putString(Keys.USER_EMAIL, info.email)
        prefs.putString(Keys.AVATAR_URL, info.avatarUrl)
    }

    suspend fun clearActiveAccount() {
        prefs.remove(Keys.ACCESS_TOKEN)
        prefs.remove(Keys.USER_LOGIN)
        prefs.remove(Keys.USER_NAME)
        prefs.remove(Keys.USER_EMAIL)
        prefs.remove(Keys.AVATAR_URL)
    }
}
