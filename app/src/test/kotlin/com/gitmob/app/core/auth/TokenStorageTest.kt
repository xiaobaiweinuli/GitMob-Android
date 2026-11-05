package com.gitmob.app.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.gitmob.app.data.model.AccountAuthMethod
import com.gitmob.app.data.model.AuthenticatedUser
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** 内存 DataStore 与替代加密仅验证存储事务，不替代 Android Keystore 实测。 */
class TokenStorageTest {
    private val user = AuthenticatedUser(42, "U_42", "renamed", "Name", "https://example.test/avatar.png")

    @Before
    fun setupCipher() {
        mockkObject(TokenCipher)
        every { TokenCipher.encrypt(any()) } answers { "cipher:${firstArg<String>()}" }
        every { TokenCipher.decrypt(any()) } answers {
            firstArg<String>().also { check(it.startsWith("cipher:")) }.removePrefix("cipher:")
        }
    }

    @After
    fun restoreCipher() {
        unmockkObject(TokenCipher)
    }

    @Test
    fun `旧键按原标签保留独立密文只迁移索引且重复快照不再写入`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf(
            stringPreferencesKey("encrypted_token_old") to "cipher:old-access",
            stringPreferencesKey("encrypted_token_Old") to "cipher:another-access",
            stringPreferencesKey("active_login") to "old",
            stringPreferencesKey("unrelated") to "keep",
        ))
        val storage = TokenStorage(store)
        val snapshot = storage.snapshot()

        assertEquals(setOf("legacy:old", "legacy:Old"), snapshot.accounts.map { it.key }.toSet())
        assertTrue(snapshot.accounts.all { it.id == null && it.nodeId == null && it.authMethod == AccountAuthMethod.LEGACY })
        assertEquals("legacy:old", snapshot.activeKey)
        val prefs = store.data.first()
        assertEquals("cipher:old-access", prefs[stringPreferencesKey("encrypted_token_old")])
        assertEquals("cipher:another-access", prefs[stringPreferencesKey("encrypted_token_Old")])
        assertEquals("keep", prefs[stringPreferencesKey("unrelated")])
        assertNull(prefs[stringPreferencesKey("active_login")])
        assertTrue(prefs[booleanPreferencesKey("stable_accounts_migrated")] == true)
        assertEquals(snapshot, storage.snapshot())
        assertEquals(1, store.writes)
        verify(exactly = 0) { TokenCipher.decrypt(any()) }
        verify(exactly = 0) { TokenCipher.encrypt(any()) }
        assertEquals("old-access", storage.credentials("legacy:old").accessToken)
    }

    @Test
    fun `没有对应旧密文的active_login不伪造账号或活动身份`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf(
            stringPreferencesKey("encrypted_token_old") to "cipher:old-access",
            stringPreferencesKey("active_login") to "missing",
        ))
        val snapshot = TokenStorage(store).snapshot()
        assertEquals(listOf("legacy:old"), snapshot.accounts.map { it.key })
        assertNull(snapshot.activeKey)
        assertNull(store.data.first()[stringPreferencesKey("active_login")])
    }

    @Test
    fun `旧标签只有经验证的previousKey被替换其他标签不会按同名合并`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf(
            stringPreferencesKey("encrypted_token_old") to "cipher:old-access",
            stringPreferencesKey("encrypted_token_renamed") to "cipher:other-access",
            stringPreferencesKey("active_login") to "old",
        ))
        val storage = TokenStorage(store)
        storage.snapshot()
        storage.saveAccount(user, AccountCredentials("verified-access"), previousKey = "legacy:old")

        val snapshot = storage.snapshot()
        assertEquals(setOf("42", "legacy:renamed"), snapshot.accounts.map { it.key }.toSet())
        assertEquals("42", snapshot.activeKey)
        assertEquals(42L, snapshot.accounts.first { it.key == "42" }.id)
        assertEquals("U_42", snapshot.accounts.first { it.key == "42" }.nodeId)
        val prefs = store.data.first()
        assertNull(prefs[stringPreferencesKey("encrypted_token_old")])
        assertNull(prefs[stringPreferencesKey("account_legacy:old_login")])
        assertEquals("cipher:other-access", prefs[stringPreferencesKey("encrypted_token_renamed")])
        assertEquals("cipher:verified-access", prefs[stringPreferencesKey("account_42_access")])
    }

    @Test
    fun `重复numeric身份更新凭据资料并移除过期refresh字段而不新增账号`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf())
        val storage = TokenStorage(store)
        storage.snapshot()
        storage.saveAccount(user, AccountCredentials("oauth-access", "oauth-refresh", 1_000, 2_000, AccountAuthMethod.DEVICE_FLOW))
        storage.requireReauthentication("42")
        storage.saveAccount(user.copy(login = "new-login", name = null, avatarUrl = null), AccountCredentials("pat-access"))

        val snapshot = storage.snapshot()
        assertEquals(1, snapshot.accounts.size)
        assertEquals("42", snapshot.activeKey)
        assertEquals("new-login", snapshot.accounts.single().login)
        assertEquals(AccountAuthMethod.PAT, snapshot.accounts.single().authMethod)
        assertFalse(snapshot.accounts.single().needsReauthentication)
        assertNull(snapshot.accounts.single().name)
        assertNull(snapshot.accounts.single().avatarUrl)
        val credentials = storage.credentials("42")
        assertEquals("pat-access", credentials.accessToken)
        assertNull(credentials.refreshToken)
        assertNull(credentials.expiresAtMillis)
        assertNull(credentials.refreshExpiresAtMillis)
        assertNull(store.data.first()[stringPreferencesKey("account_42_refresh")])
    }

    @Test
    fun `令牌对加密保存原样取回而identity更新不修改或暴露凭据`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf())
        val storage = TokenStorage(store)
        storage.snapshot()
        val pair = AccountCredentials("access", "refresh", 1_000, 2_000, AccountAuthMethod.DEVICE_FLOW)
        storage.saveAccount(user, pair)
        val before = store.data.first()
        storage.updateIdentity(user.copy(name = "New name"))
        val after = store.data.first()

        assertEquals("cipher:access", after[stringPreferencesKey("account_42_access")])
        assertEquals("cipher:refresh", after[stringPreferencesKey("account_42_refresh")])
        for (key in listOf("account_42_access", "account_42_refresh", "account_42_method")) {
            assertEquals(before[stringPreferencesKey(key)], after[stringPreferencesKey(key)])
        }
        assertEquals(1_000L, after[longPreferencesKey("account_42_expires")])
        assertEquals(2_000L, after[longPreferencesKey("account_42_refresh_expires")])
        val decoded = storage.credentials("42")
        assertEquals(pair.accessToken, decoded.accessToken)
        assertEquals(pair.refreshToken, decoded.refreshToken)
        assertEquals(pair.expiresAtMillis, decoded.expiresAtMillis)
        assertEquals(pair.refreshExpiresAtMillis, decoded.refreshExpiresAtMillis)
    }

    @Test
    fun `保存迁移失败原子保留旧索引活动账号和密文`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf(
            stringPreferencesKey("encrypted_token_old") to "cipher:old-access",
            stringPreferencesKey("active_login") to "old",
        ))
        val storage = TokenStorage(store)
        val before = storage.snapshot()
        val prefs = store.data.first()
        store.failWrite = true
        try {
            storage.saveAccount(user, AccountCredentials("new-access", "new-refresh", authMethod = AccountAuthMethod.DEVICE_FLOW), "legacy:old")
            throw AssertionError("Expected storage failure")
        } catch (_: IOException) { }
        assertEquals(prefs, store.data.first())
        assertEquals(before, storage.snapshot())
    }

    @Test
    fun `保存失败和错误退出目标不得删除任何账号且正确退出仅删除当前账号`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf())
        val storage = TokenStorage(store)
        storage.snapshot()
        storage.saveAccount(user, AccountCredentials("first-access", "first-refresh", 1_000, 2_000, AccountAuthMethod.DEVICE_FLOW))
        storage.saveAccount(user.copy(id = 2, nodeId = "U_2", login = "second"), AccountCredentials("second-access"), setActive = false)
        val before = store.data.first()
        try {
            storage.clearActiveAccount("2")
            throw AssertionError("Expected identity mismatch")
        } catch (_: IllegalStateException) { }
        assertEquals(before, store.data.first())
        store.failWrite = true
        try {
            storage.saveCredentials("42", AccountCredentials("rotated-access", "rotated-refresh"))
            throw AssertionError("Expected storage failure")
        } catch (_: IOException) { }
        assertEquals(before, store.data.first())
        store.failWrite = false
        storage.clearActiveAccount("42")
        val snapshot = storage.snapshot()
        assertEquals(listOf("2"), snapshot.accounts.map { it.key })
        assertNull(snapshot.activeKey)
        assertEquals("second-access", storage.credentials("2").accessToken)
        assertTrue(store.data.first().asMap().keys.none { it.name.startsWith("account_42_") })
    }

    @Test
    fun `标记重新授权保留密文且不删除其他活动账号`() = runTest {
        val store = MemoryPreferences(mutablePreferencesOf())
        val storage = TokenStorage(store)
        storage.snapshot()
        storage.saveAccount(user, AccountCredentials("first-access"))
        storage.saveAccount(user.copy(id = 2, nodeId = "U_2", login = "second"), AccountCredentials("second-access"))
        storage.requireReauthentication("42")

        val snapshot = storage.snapshot()
        assertEquals("2", snapshot.activeKey)
        assertTrue(snapshot.accounts.first { it.key == "42" }.needsReauthentication)
        assertEquals("first-access", storage.credentials("42").accessToken)
        assertEquals(setOf("2", "42"), store.data.first()[stringSetPreferencesKey("account_ids")])
    }

    private class MemoryPreferences(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial.toPreferences())
        private val changes = Mutex()
        override val data: Flow<Preferences> = state
        var failWrite = false
        var writes = 0

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = changes.withLock {
            val updated = transform(state.value)
            if (failWrite) throw IOException("Storage unavailable")
            state.value = updated.toPreferences()
            writes++
            state.value
        }
    }
}
