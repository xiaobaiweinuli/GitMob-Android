package com.gitmob.android.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.gitmob.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GitHubAppManager {

    private const val GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
    private const val SCOPES = "repo,user,delete_repo,workflow,notifications"

    // GitHub App Worker 基础 URL
    private val WORKER_BASE: String
        get() = BuildConfig.GITHUB_APP_REDIRECT_URI
            .removeSuffix("/github/callback")
            .trimEnd('/')

    // 专用裸 OkHttp 客户端，不带 GitHub token 拦截器
    private val workerClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 启动 GitHub App 授权页面。
     *
     * @param forceReauth true = 追加 prompt=consent，强制弹出授权确认页
     */
    fun launchGitHubAppAuth(context: Context, forceReauth: Boolean = false) {
        val uri = Uri.parse(GITHUB_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id",    BuildConfig.GITHUB_APP_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.GITHUB_APP_REDIRECT_URI)
            .appendQueryParameter("scope",        SCOPES)
            .appendQueryParameter("state",        (1..16).map { ('a'..'z').random() }.joinToString(""))
            .apply { if (forceReauth) appendQueryParameter("prompt", "consent") }
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    }

    /**
     * 使用 refresh token 刷新 access token。
     *
     * @param refreshToken GitHub App refresh token
     * @return 刷新结果，包含新的 access token、refresh token 和过期时间戳（毫秒）
     */
    suspend fun refreshToken(refreshToken: String): RefreshResult? = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", BuildConfig.GITHUB_APP_CLIENT_ID)
                .build()

            val req = Request.Builder()
                .url("$WORKER_BASE/github/refresh")
                .post(formBody)
                .build()

            workerClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                // 简化解析，实际应该用 Gson
                val accessToken = Regex("\"access_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                val newRefreshToken = Regex("\"refresh_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                val expiresIn = Regex("\"expires_in\":(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()

                if (accessToken == null) return@withContext null

                val expiresAt = expiresIn?.let { System.currentTimeMillis() + (it * 1000) }
                RefreshResult(accessToken, newRefreshToken, expiresAt)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 撤销 Token（DELETE Worker /github/token）。
     */
    suspend fun revokeToken(token: String): Boolean = workerDelete("/github/token", token)

    /**
     * 删除授权 Grant（DELETE Worker /github/grant）。
     */
    suspend fun deleteGrant(token: String): Boolean = workerDelete("/github/grant", token)

    // ── 内部 ────────────────────────────────────────────────────────
    private suspend fun workerDelete(path: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$WORKER_BASE$path")
                    .delete("{}".toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .build()
                workerClient.newCall(req).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }

    data class RefreshResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long?
    )
}
