package com.gitmob.android.auth

import com.gitmob.android.api.ApiClient
import com.gitmob.android.api.GHUser
import com.gitmob.android.util.LogManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Token 登录验证结果
 */
sealed class TokenLoginResult {
    /** 验证成功 */
    data class Success(
        val login: String,
        val name: String,
        val email: String,
        val avatarUrl: String,
    ) : TokenLoginResult()

    /** 验证失败 */
    data class Failure(val message: String) : TokenLoginResult()

    /** 权限不全 */
    data class InsufficientScopes(val missingScopes: List<String>) : TokenLoginResult()
}

/**
 * Token 登录管理器
 *
 * 功能：
 * - 验证 Token 有效性
 * - 检查权限范围
 * - 获取用户信息
 */
object TokenLoginManager {

    private const val TAG = "TokenLoginManager"

    /** 必须的权限列表 */
    private val REQUIRED_SCOPES = listOf(
        "delete_repo",
        "notifications",
        "repo",
        "user",
        "workflow"
    )

    private val gson = Gson()

    /**
     * 验证 Token 并获取用户信息
     *
     * @param token GitHub Personal Access Token
     * @return 验证结果
     */
    suspend fun verifyToken(token: String): TokenLoginResult = withContext(Dispatchers.IO) {
        val context = com.gitmob.android.GitMobApp.instance
        val tokenStorage = TokenStorage(context)
        val originalToken: String? = tokenStorage.accessToken.first()
        
        try {
            LogManager.d(TAG, "开始验证 Token...")
            LogManager.d(TAG, "Token 前缀: ${token.take(20)}...")
            LogManager.d(TAG, "当前 TokenStorage token: ${originalToken?.take(20)}...")
            
            // 使用 rawHttpClient 直接请求，避免 ApiClient 的 401 拦截器影响
            val client = ApiClient.rawHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2026-03-10")
                .build()

            LogManager.d(TAG, "发送请求到 GitHub API...")
            val response = client.newCall(request).execute()
            LogManager.d(TAG, "收到响应: HTTP ${response.code}")
            
            // 检查响应状态
            if (!response.isSuccessful) {
                LogManager.e(TAG, "Token 验证失败: HTTP ${response.code}")
                val errorBody = response.body?.string()
                LogManager.e(TAG, "错误响应内容: $errorBody")
                return@withContext when (response.code) {
                    401 -> TokenLoginResult.Failure("Token 无效、已过期或已被撤销")
                    else -> TokenLoginResult.Failure("验证失败: HTTP ${response.code}")
                }
            }

            // 检查权限范围
            val scopesHeader = response.header("x-oauth-scopes")
            LogManager.d(TAG, "x-oauth-scopes: $scopesHeader")
            
            val missingScopes = checkScopes(scopesHeader)
            if (missingScopes.isNotEmpty()) {
                LogManager.w(TAG, "权限不全，缺少: $missingScopes")
                return@withContext TokenLoginResult.InsufficientScopes(missingScopes)
            }

            // 解析用户信息
            val responseBody = response.body?.string() ?: return@withContext TokenLoginResult.Failure("无法获取用户信息")
            val user = gson.fromJson(responseBody, GHUser::class.java)
            LogManager.d(TAG, "Token 验证成功，用户: ${user.login}")

            TokenLoginResult.Success(
                login = user.login,
                name = user.name ?: user.login,
                email = user.email ?: "${user.login}@users.noreply.github.com",
                avatarUrl = user.avatarUrl ?: ""
            )
        } catch (e: Exception) {
            LogManager.e(TAG, "Token 验证异常", e)
            TokenLoginResult.Failure("验证失败: ${e.message}")
        } finally {
            // 恢复原来的 token（如果需要的话，但这里我们只是验证，不应该修改当前状态）
            // 注意：因为我们使用 rawHttpClient，所以不需要重建 ApiClient
            // 保持原样即可
        }
    }

    /**
     * 检查权限是否完整
     *
     * @param scopesHeader x-oauth-scopes 响应头内容
     * @return 缺失的权限列表
     */
    private fun checkScopes(scopesHeader: String?): List<String> {
        if (scopesHeader.isNullOrBlank()) {
            return REQUIRED_SCOPES
        }

        val scopes = scopesHeader.split(",").map { it.trim() }
        return REQUIRED_SCOPES.filter { required ->
            !scopes.any { scope -> scope == required || scope.startsWith("$required:") }
        }
    }
}
