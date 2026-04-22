package com.gitmob.android.util

import android.content.Context
import com.gitmob.android.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * GitHub Emoji 管理工具，用于获取、存储和转换 emoji 标记
 */
object EmojiManager {

    private const val FILE_NAME = "emojis.json"
    private const val TAG = "EmojiManager"
    private const val GITHUB_EMOJI_API = "https://api.github.com/emojis"

    // 内存缓存
    @Volatile
    private var cache: Map<String, String>? = null

    // ── 内部存储文件 ──────────────────────────────────────────────────────────

    private fun jsonFile(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * 检查本地是否有 emoji 数据文件
     */
    fun hasEmojiData(context: Context): Boolean = jsonFile(context).exists()

    // ── 读取 ──────────────────────────────────────────────────────────────────

    /**
     * 从本地 emojis.json 读取 emoji 映射表
     * 结果会缓存在内存中，再次调用直接返回缓存
     */
    fun loadEmojis(context: Context): Map<String, String> {
        cache?.let { return it }
        val file = jsonFile(context)
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.getString(key)
            }
            cache = map
            map
        } catch (e: Exception) {
            LogManager.e(TAG, "读取 emojis.json 失败", e)
            emptyMap()
        }
    }

    /**
     * 清除内存缓存（文件更新后调用）
     */
    fun invalidateCache() {
        cache = null
    }

    // ── 获取 & 解析 ───────────────────────────────────────────────────────────

    /**
     * 从 GitHub API 获取 emoji 数据，解析后保存为 emojis.json
     *
     * 注意：GitHub API 返回的是图片 URL，我们需要从中提取 unicode 字符
     * URL 格式示例：https://github.githubassets.com/images/icons/emoji/unicode/1f44b.png
     */
    suspend fun fetchAndSave(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // 1. 调用 GitHub Emoji API（无需认证）
            val request = Request.Builder()
                .url(GITHUB_EMOJI_API)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2026-03-10")
                .build()

            val client = ApiClient.rawHttpClient()
            val response = client.newCall(request).execute()

            LogManager.d(TAG, "Emoji API HTTP 响应码: ${response.code}")

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val jsonString = response.body?.string()
                ?: return@withContext Result.failure(Exception("响应体为空"))

            LogManager.d(TAG, "Emoji API 响应长度: ${jsonString.length}")

            val json = JSONObject(jsonString)
            val emojiMap = mutableMapOf<String, String>()

            // 2. 解析响应，提取 unicode 字符
            json.keys().forEach { key ->
                val url = json.getString(key)
                try {
                    val emojiChar = urlToEmoji(url)
                    emojiMap[key] = emojiChar
                } catch (e: Exception) {
                    LogManager.w(TAG, "无法解析 emoji: $key, URL: $url", e)
                }
            }

            LogManager.d(TAG, "成功解析 ${emojiMap.size} 个 emoji")

            // 3. 保存到本地文件
            val outputJson = JSONObject(emojiMap)
            jsonFile(context).writeText(outputJson.toString())

            // 4. 刷新内存缓存
            invalidateCache()
            cache = emojiMap

            LogManager.i(TAG, "Emoji 数据获取成功，共 ${emojiMap.size} 个")
            Result.success(emojiMap.size)

        } catch (e: Exception) {
            LogManager.e(TAG, "fetchAndSave 失败", e)
            Result.failure(e)
        }
    }

    /**
     * 从 emoji 图片 URL 提取 emoji 字符
     * URL 格式：https://github.githubassets.com/images/icons/emoji/unicode/1f44b.png
     */
    private fun urlToEmoji(url: String): String {
        val fileName = url.substringAfterLast('/')
        val unicodeHex = fileName.substringBeforeLast('.')

        // 处理多个码点的情况（例如：1f468-200d-1f469-200d-1f467-200d-1f466）
        val codePoints = unicodeHex.split('-').map { it.toInt(16) }
        return String(codePoints.toIntArray(), 0, codePoints.size)
    }

    // ── emoji 标记转换 ───────────────────────────────────────────────────────────

    /**
     * 将文本中的 emoji 标记（如 :smile:）转换为实际的 emoji 字符
     *
     * @param text 包含 emoji 标记的文本
     * @param context 应用 Context（用于加载 emoji 数据）
     * @return 转换后的文本
     */
    fun replaceEmojiMarkdown(text: String, context: Context? = null): String {
        var result = text
        val emojiMap = if (context != null) loadEmojis(context) else cache ?: emptyMap()
        
        if (emojiMap.isEmpty()) {
            return result
        }

        // 使用正则表达式匹配 :key: 格式的标记
        val pattern = Regex(""":([a-zA-Z0-9_+-]+):""")
        return pattern.replace(result) { match ->
            val key = match.groupValues[1]
            emojiMap[key] ?: match.value
        }
    }
}
