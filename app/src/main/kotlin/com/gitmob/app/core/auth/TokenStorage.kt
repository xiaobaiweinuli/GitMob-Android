package com.gitmob.app.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("secure_token_store")

/**
 * 多账号 token 存储：key 按 login 维度拼接，activeLoginKey 记录当前激活账号。
 * 非敏感信息（login/name/avatar）不要跟着一起走 Keystore 加解密，用普通明文 DataStore 即可。
 */
@Singleton
class TokenStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccessTokenProvider {

    private fun tokenKey(login: String) = stringPreferencesKey("encrypted_token_$login")
    private val activeLoginKey = stringPreferencesKey("active_login")

    val activeLogin: Flow<String?> = context.dataStore.data.map { it[activeLoginKey] }

    /** 读取当前激活账号的 token */
    override suspend fun getToken(): String? {
        val prefs = context.dataStore.data.first()
        val login = prefs[activeLoginKey] ?: return null
        val encrypted = prefs[tokenKey(login)] ?: return null
        return try {
            TokenCipher.decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    /** 保存 token（加密后写入 DataStore）。登录成功/失败回滚路径都会调用 */
    suspend fun saveToken(login: String, token: String, setActive: Boolean = true) {
        try {
            context.dataStore.edit { prefs ->
                prefs[tokenKey(login)] = TokenCipher.encrypt(token)
                if (setActive) prefs[activeLoginKey] = login
            }
        } catch (e: Exception) {
            throw e
        }
    }

    suspend fun removeAccount(login: String) {
        context.dataStore.edit { prefs -> prefs.remove(tokenKey(login)) }
    }

    suspend fun isLoggedIn(): Boolean = getToken() != null
}
