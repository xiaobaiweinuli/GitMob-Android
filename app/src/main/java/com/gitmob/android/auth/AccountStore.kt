package com.gitmob.android.auth

import android.content.Context
import com.gitmob.android.util.EncryptedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * GitHub Token 前缀识别工具
 */
object GitHubTokenPrefix {
    /** OAuth Token（第三方登录） */
    const val OAUTH = "gho_"

    /** Classic PAT（旧版个人访问令牌） */
    const val CLASSIC_PAT = "ghp_"

    /** Fine-grained PAT（新版细粒度个人访问令牌） */
    const val FINE_GRAINED_PAT = "github_pat_"

    /** App User Token（GitHub App 用户 token） */
    const val APP_USER = "ghu_"

    /** App Installation Token（GitHub App 安装 token / Actions token） */
    const val APP_INSTALLATION = "ghs_"
}

/**
 * 认证类型：OAuth 授权登录 或 Token 手动登录
 */
enum class AuthType {
    @SerializedName("oauth")
    OAUTH,  // OAuth 授权登录（默认）

    @SerializedName("token")
    TOKEN   // 手动 Token 登录
}

/**
 * 单个账号的完整信息（所有数据加密存储）
 *
 * 所有字段均标注 @SerializedName，确保 R8 混淆后 Gson 仍能按 JSON key 正确反序列化，
 * 与 proguard-rules.pro 的 -keepclassmembers ... @SerializedName 规则配合双重保护。
 */
data class AccountInfo(
    @SerializedName("login")     val login: String,
    @SerializedName("name")      val name: String,
    @SerializedName("email")     val email: String,
    @SerializedName("avatarUrl") val avatarUrl: String,
    @SerializedName("token")     val token: String,
    @SerializedName("authType")  val authType: AuthType = AuthType.OAUTH,
) {
    val displayName: String get() = name.ifBlank { login }
}

/**
 * 多账号存储管理器（使用 EncryptedPreferences）
 *
 * 数据模型：
 *   accounts_json  → List<AccountInfo> JSON（所有已授权账号，加密存储）
 *   active_login   → 当前活跃账号的 login
 *
 * 与 TokenStorage 协同工作：
 *   TokenStorage 保留单账号字段作为"当前活跃账号"的镜像，供其他组件兼容读取。
 *   AccountStore 管理完整的多账号列表。
 *
 * 注意：不使用 TypeToken<List<AccountInfo>>(){}，改用 Array<AccountInfo> 再转 List，
 *       彻底规避 R8 在 release 包中裁剪泛型签名导致的 IllegalStateException。
 */
class AccountStore(private val context: Context) {

    companion object {
        /**
         * 根据 GitHub Token 前缀自动识别认证类型
         *
         * @param token GitHub Token
         * @return 识别的认证类型
         */
        fun identifyAuthType(token: String): AuthType {
            return when {
                token.startsWith(GitHubTokenPrefix.OAUTH) -> AuthType.OAUTH
                else -> AuthType.TOKEN
            }
        }
    }

    private val gson = Gson()
    private val prefs = EncryptedPreferences.getInstance(context)

    private object Keys {
        const val ACCOUNTS_JSON = "accounts_json"
        const val ACTIVE_LOGIN  = "active_login"
    }

    /**
     * 将 JSON 反序列化为 List<AccountInfo>。
     * 用 JsonParser 解析为 JsonArray，逐元素用 AccountInfo::class.java 反序列化。
     * 完全不依赖泛型签名，debug/release 行为一致，R8 安全。
     */
    private fun parseAccounts(json: String?): List<AccountInfo> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = com.google.gson.JsonParser.parseString(json).asJsonArray
            array.mapNotNull { element ->
                try { gson.fromJson(element, AccountInfo::class.java) }
                catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 所有已保存账号的流 */
    val accounts: Flow<List<AccountInfo>> = prefs.getStringFlow(Keys.ACCOUNTS_JSON, "[]")
        .map { parseAccounts(it) }

    /** 当前活跃账号的 login */
    val activeLogin: Flow<String?> = prefs.getStringFlow(Keys.ACTIVE_LOGIN)

    /** 当前活跃账号信息 */
    val activeAccount: Flow<AccountInfo?> = prefs.getStringFlow(Keys.ACTIVE_LOGIN).map { login ->
        if (login == null) return@map null
        parseAccounts(prefs.getString(Keys.ACCOUNTS_JSON)).firstOrNull { it.login == login }
    }

    /** 添加或更新账号（login 相同则覆盖），并设为活跃账号 */
    suspend fun addOrUpdateAccount(info: AccountInfo) {
        val current = parseAccounts(prefs.getString(Keys.ACCOUNTS_JSON))
        val updated = current.filter { it.login != info.login } + info
        prefs.putString(Keys.ACCOUNTS_JSON, gson.toJson(updated))
        prefs.putString(Keys.ACTIVE_LOGIN, info.login)
    }

    /** 切换活跃账号，返回目标账号信息（不存在则返回 null） */
    suspend fun switchAccount(login: String): AccountInfo? {
        val list = accounts.first()
        val target = list.firstOrNull { it.login == login } ?: return null
        prefs.putString(Keys.ACTIVE_LOGIN, login)
        return target
    }

    /**
     * 移除账号。
     * @return 移除后的剩余账号列表
     */
    suspend fun removeAccount(login: String): List<AccountInfo> {
        var remaining: List<AccountInfo> = emptyList()
        val current = parseAccounts(prefs.getString(Keys.ACCOUNTS_JSON))
        remaining = current.filter { it.login != login }
        prefs.putString(Keys.ACCOUNTS_JSON, gson.toJson(remaining))

        // 如果移除的是活跃账号，切换到第一个剩余账号（或清空）
        val activeWasRemoved = prefs.getString(Keys.ACTIVE_LOGIN) == login
        if (activeWasRemoved) {
            if (remaining.isNotEmpty()) {
                prefs.putString(Keys.ACTIVE_LOGIN, remaining.first().login)
            } else {
                prefs.remove(Keys.ACTIVE_LOGIN)
            }
        }
        return remaining
    }

    /** 获取指定账号的 token（用于撤销操作） */
    suspend fun getToken(login: String): String? =
        accounts.first().firstOrNull { it.login == login }?.token

    /** 清空所有账号数据 */
    suspend fun clearAll() {
        prefs.remove(Keys.ACCOUNTS_JSON)
        prefs.remove(Keys.ACTIVE_LOGIN)
    }
}
