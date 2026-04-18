package com.gitmob.android.util

import android.content.Context
import com.gitmob.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 应用更新管理器
 * 统一管理更新检测逻辑
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    private const val GITHUB_API_BASE = "https://api.github.com"
    private const val REPO_OWNER = "xiaobaiweinuli"
    private const val REPO_NAME = "GitMob-Android"

    /**
     * 发布版本信息
     */
    data class Release(
        val tagName: String,
        val name: String,
        val body: String,
        val publishedAt: String,
        val apkUrl: String?
    )

    /**
     * 检测是否有新版本
     * @param token 用户认证token，如果为null则不进行检测
     */
    suspend fun checkForUpdate(token: String?): Result<Release?> = withContext(Dispatchers.IO) {
        try {
            if (token.isNullOrBlank()) {
                LogManager.d(TAG, "未提供认证token，跳过更新检测")
                return@withContext Result.success(null)
            }

            val url = "$GITHUB_API_BASE/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "GitMob-Android")

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val request = requestBuilder.build()

            LogManager.d(TAG, "开始检测更新，当前版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                LogManager.e(TAG, "检测更新失败: HTTP ${response.code}")
                return@withContext Result.success(null)
            }

            val responseBody = response.body?.string() ?: return@withContext Result.success(null)
            
            val json = JSONObject(responseBody)

            val tagName = json.getString("tag_name")
            val name = json.optString("name", tagName)
            val body = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.getString("name")
                    if (assetName.endsWith(".apk")) {
                        apkUrl = asset.getString("url")
                        break
                    }
                }
            }

            val release = Release(
                tagName = tagName,
                name = name,
                body = body,
                publishedAt = publishedAt,
                apkUrl = apkUrl
            )

            LogManager.i(TAG, "检测到最新版本: ${release.tagName}")

            if (isNewerVersion(release)) {
                LogManager.i(TAG, "发现新版本!")
                Result.success(release)
            } else {
                LogManager.i(TAG, "当前已是最新版本")
                Result.success(null)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "检测更新异常", e)
            Result.failure(e)
        }
    }

    /**
     * 判断是否是新版本
     */
    fun isNewerVersion(release: Release): Boolean {
        val currentVersion = BuildConfig.VERSION_NAME
        val releaseVersion = release.tagName.removePrefix("v")

        LogManager.d(TAG, "版本比较 - 当前版本: $currentVersion, 最新版本: $releaseVersion")
        val result = compareVersionStrings(releaseVersion, currentVersion) > 0
        
        LogManager.d(TAG, "版本比较结果: ${if (result) "有新版本" else "已是最新"}")
        return result
    }

    /**
     * 比较版本字符串
     * 返回 1: version1 > version2
     * 返回 -1: version1 < version2
     * 返回 0: 相等
     */
    private fun compareVersionStrings(version1: String, version2: String): Int {
        val parts1 = version1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = version2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val v1 = parts1.getOrNull(i) ?: 0
            val v2 = parts2.getOrNull(i) ?: 0

            if (v1 > v2) return 1
            if (v1 < v2) return -1
        }

        return 0
    }

    /**
     * 获取被忽略的版本
     */
    fun getIgnoredVersion(context: Context): String? {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        return prefs.getString("ignored_version", null)
    }

    /**
     * 忽略某个版本
     */
    fun ignoreVersion(context: Context, version: String) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("ignored_version", version).apply()
        LogManager.i(TAG, "已忽略版本: $version")
    }

    /**
     * 检查是否应该显示更新弹窗
     */
    fun shouldShowUpdateDialog(context: Context, release: Release): Boolean {
        val ignored = getIgnoredVersion(context)
        
        if (ignored == null) {
            return true
        }
        
        val ignoredVersion = ignored.removePrefix("v")
        val releaseVersion = release.tagName.removePrefix("v")
        val isIgnoredVersionOlder = compareVersionStrings(releaseVersion, ignoredVersion) > 0
        
        if (isIgnoredVersionOlder) {
            LogManager.i(TAG, "检测到新版本比忽略版本新，清除忽略版本 - 忽略版本: $ignored, 新版本: ${release.tagName}")
            clearIgnoredVersion(context)
            return true
        }
        
        return ignored != release.tagName
    }
    
    /**
     * 清除被忽略的版本
     */
    fun clearIgnoredVersion(context: Context) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("ignored_version").apply()
        LogManager.i(TAG, "已清除忽略版本")
    }
}
