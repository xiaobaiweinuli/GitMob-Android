package com.gitmob.app.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gitmob.app.core.error.UnauthorizedException
import com.gitmob.app.data.model.AccountAuthMethod
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.GitHubAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("secure_token_store")

data class StoredAccounts(val accounts: List<GitHubAccount>, val activeKey: String?)

/** 稳定 numeric ID 存储；旧 token 在身份验证前原样保留，绝不按标签合并。 */
@Singleton
class TokenStorage internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.dataStore)

    private val accountKeys = stringSetPreferencesKey("account_ids")
    private val activeKey = stringPreferencesKey("active_account")
    private val migrated = booleanPreferencesKey("stable_accounts_migrated")
    private fun text(key: String, field: String) = stringPreferencesKey("account_${key}_$field")
    private fun expiry(key: String, field: String) = longPreferencesKey("account_${key}_$field")
    private fun reauth(key: String) = booleanPreferencesKey("account_${key}_reauth")
    private fun access(key: String) = if (key.startsWith("legacy:")) {
        stringPreferencesKey("encrypted_token_${key.removePrefix("legacy:")}")
    } else text(key, "access")

    suspend fun snapshot(): StoredAccounts {
        if (store.data.first()[migrated] != true) {
            store.edit { prefs ->
                if (prefs[migrated] == true) return@edit
                val legacyKeys = prefs.asMap().keys.mapNotNull { pref ->
                    pref.name.takeIf { it.startsWith("encrypted_token_") }
                        ?.removePrefix("encrypted_token_")
                }
                prefs[accountKeys] = prefs[accountKeys].orEmpty() + legacyKeys.map { "legacy:$it" }
                legacyKeys.forEach { login ->
                    prefs[text("legacy:$login", "login")] = login
                    prefs[text("legacy:$login", "method")] = AccountAuthMethod.LEGACY.name
                }
                prefs[stringPreferencesKey("active_login")]?.let { login ->
                    if (login in legacyKeys) prefs[activeKey] = "legacy:$login"
                }
                prefs.remove(stringPreferencesKey("active_login"))
                prefs[migrated] = true
            }
        }
        val prefs = store.data.first()
        val accounts = prefs[accountKeys].orEmpty().sorted().map { key ->
            GitHubAccount(
                key = key,
                id = key.toLongOrNull(),
                nodeId = prefs[text(key, "node")],
                login = prefs[text(key, "login")].orEmpty(),
                name = prefs[text(key, "name")],
                avatarUrl = prefs[text(key, "avatar")],
                authMethod = prefs[text(key, "method")]?.let(AccountAuthMethod::valueOf)
                    ?: AccountAuthMethod.PAT,
                needsReauthentication = prefs[reauth(key)] == true,
            )
        }
        return StoredAccounts(accounts, prefs[activeKey])
    }

    suspend fun credentials(key: String): AccountCredentials = withContext(Dispatchers.IO) {
        val prefs = store.data.first()
        val encrypted = prefs[access(key)] ?: throw UnauthorizedException()
        AccountCredentials(
            accessToken = TokenCipher.decrypt(encrypted),
            refreshToken = prefs[text(key, "refresh")]?.let(TokenCipher::decrypt),
            expiresAtMillis = prefs[expiry(key, "expires")],
            refreshExpiresAtMillis = prefs[expiry(key, "refresh_expires")],
            authMethod = prefs[text(key, "method")]?.let(AccountAuthMethod::valueOf)
                ?: AccountAuthMethod.PAT,
        )
    }

    suspend fun saveAccount(
        user: AuthenticatedUser,
        credentials: AccountCredentials,
        previousKey: String? = null,
        setActive: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        require(user.id > 0 && user.nodeId.isNotBlank() && user.login.isNotBlank())
        val encryptedAccess = TokenCipher.encrypt(credentials.accessToken)
        val encryptedRefresh = credentials.refreshToken?.let(TokenCipher::encrypt)
        store.edit { prefs ->
            val key = user.id.toString()
            if (previousKey != null && previousKey != key) remove(prefs, previousKey)
            prefs[accountKeys] = prefs[accountKeys].orEmpty() + key
            putIdentity(prefs, key, user)
            putCredentials(prefs, key, credentials, encryptedAccess, encryptedRefresh)
            if (setActive) prefs[activeKey] = key
        }
    }

    suspend fun saveCredentials(key: String, credentials: AccountCredentials) = withContext(Dispatchers.IO) {
        val encryptedAccess = TokenCipher.encrypt(credentials.accessToken)
        val encryptedRefresh = credentials.refreshToken?.let(TokenCipher::encrypt)
        store.edit { prefs ->
            check(key in prefs[accountKeys].orEmpty()) { "Account no longer exists" }
            putCredentials(prefs, key, credentials, encryptedAccess, encryptedRefresh)
        }
    }

    suspend fun updateIdentity(user: AuthenticatedUser) {
        store.edit { prefs ->
            val key = user.id.toString()
            check(key in prefs[accountKeys].orEmpty()) { "Account no longer exists" }
            putIdentity(prefs, key, user)
        }
    }

    suspend fun activate(key: String) {
        store.edit { prefs ->
            check(key in prefs[accountKeys].orEmpty()) { "Account no longer exists" }
            prefs[activeKey] = key
        }
    }

    suspend fun requireReauthentication(key: String) {
        store.edit { prefs ->
            if (key in prefs[accountKeys].orEmpty()) prefs[reauth(key)] = true
            if (prefs[activeKey] == key) prefs.remove(activeKey)
        }
    }

    /** 只删除当前账号本地凭据；其余账号保留，不自动激活、不撤销 GitHub 授权。 */
    suspend fun clearActiveAccount(expectedKey: String) {
        store.edit { prefs ->
            check(prefs[activeKey] == expectedKey) { "Active account changed before logout" }
            remove(prefs, expectedKey)
            prefs.remove(activeKey)
        }
    }

    private fun putIdentity(prefs: MutablePreferences, key: String, user: AuthenticatedUser) {
        prefs[text(key, "login")] = user.login
        prefs[text(key, "node")] = user.nodeId
        user.name?.let { prefs[text(key, "name")] = it } ?: prefs.remove(text(key, "name"))
        user.avatarUrl?.let { prefs[text(key, "avatar")] = it } ?: prefs.remove(text(key, "avatar"))
    }

    private fun putCredentials(
        prefs: MutablePreferences, key: String, credentials: AccountCredentials,
        encryptedAccess: String, encryptedRefresh: String?,
    ) {
        prefs[access(key)] = encryptedAccess
        encryptedRefresh?.let { prefs[text(key, "refresh")] = it } ?: prefs.remove(text(key, "refresh"))
        credentials.expiresAtMillis?.let { prefs[expiry(key, "expires")] = it } ?: prefs.remove(expiry(key, "expires"))
        credentials.refreshExpiresAtMillis?.let { prefs[expiry(key, "refresh_expires")] = it }
            ?: prefs.remove(expiry(key, "refresh_expires"))
        prefs[text(key, "method")] = credentials.authMethod.name
        prefs.remove(reauth(key))
    }

    private fun remove(prefs: MutablePreferences, key: String) {
        prefs[accountKeys] = prefs[accountKeys].orEmpty() - key
        prefs.remove(access(key))
        listOf("access", "refresh", "login", "node", "name", "avatar", "method").forEach { prefs.remove(text(key, it)) }
        prefs.remove(expiry(key, "expires"))
        prefs.remove(expiry(key, "refresh_expires"))
        prefs.remove(reauth(key))
    }
}
