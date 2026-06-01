package com.gitmob.android.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.gitmob.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object OAuthManager {

    private const val GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
    private const val SCOPES = "repo,user,delete_repo,workflow,notifications,admin.org"

    // Worker 基础 URL（去掉 /callback 后缀），所有 token/grant 操作走 Worker
    private val WORKER_BASE: String
        get() = BuildConfig.OAUTH_REDIRECT_URI
            .removeSuffix("/callback")
            .trimEnd('/')

    // 专用裸 OkHttp 客户端，不带 GitHub token 拦截器
    private val workerClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 启动 GitHub OAuth 授权页面。
     *
     * @param forceReauth true = 追加 prompt=consent，强制弹出授权确认页
     *                    （退出登录后重新授权场景）
     */
    fun launchOAuth(context: Context, forceReauth: Boolean = false) {
        val uri = Uri.parse(GITHUB_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id",    BuildConfig.GITHUB_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope",        SCOPES)
            .appendQueryParameter("state",        (1..16).map { ('a'..'z').random() }.joinToString(""))
            .apply { if (forceReauth) appendQueryParameter("prompt", "consent") }
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    }

    /**
     * 撤销 Token（DELETE Worker /token）。
     *
     * 仅使当前 access_token 失效，授权 Grant 记录保留。
     * GitHub Settings → Applications 仍可见 GitMob，
     * 下次授权可跳过选权限直接获取新 token。
     *
     * 适用于：普通退出登录。
     */
    suspend fun revokeToken(token: String): Boolean = workerDelete("/token", token)

    /**
     * 删除授权 Grant（DELETE Worker /grant）。
     *
     * 彻底移除 GitMob 在 GitHub 账号的所有 OAuth 授权（含全部关联 token）。
     * 用户在 GitHub Settings → Applications 中将看不到 GitMob，
     * 下次需重新完整授权。
     *
     * 适用于：彻底注销/取消所有授权。
     */
    suspend fun deleteGrant(token: String): Boolean = workerDelete("/grant", token)

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
}
