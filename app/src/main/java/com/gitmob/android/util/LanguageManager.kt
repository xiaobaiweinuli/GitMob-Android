package com.gitmob.android.util

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.gitmob.android.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * GitHub Linguist 语言条目
 * @param id   小写 + 空格转连字符（与 GitHub Search API language 参数一致，用于筛选查询）
 * @param name 原始语言名称（与仓库 API 返回的 `language` 字段一致，用于本地过滤匹配）
 * @param color 十六进制颜色字符串，可为 null（无色用透明/默认色显示）
 */
data class LanguageEntry(
    val id: String,
    val name: String,
    val color: String?,
)

object LanguageManager {

    private const val FILE_NAME = "languages.json"
    private const val TAG = "LanguageManager"

    // 内存缓存
    @Volatile
    private var cache: List<LanguageEntry>? = null

    // ── 内部存储文件 ──────────────────────────────────────────────────────────

    private fun jsonFile(context: Context) = File(context.filesDir, FILE_NAME)

    /** 语言数据文件是否已存在 */
    fun hasLanguageData(context: Context): Boolean = jsonFile(context).exists()

    // ── 读取 ──────────────────────────────────────────────────────────────────

    /**
     * 从本地 languages.json 读取语言列表，返回空列表表示尚未获取。
     * 结果会缓存在内存中，再次调用直接返回缓存。
     */
    fun loadLanguages(context: Context): List<LanguageEntry> {
        cache?.let { return it }
        val file = jsonFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                LanguageEntry(
                    id    = obj.getString("id"),
                    name  = obj.getString("name"),
                    color = obj.optString("color").ifBlank { null },
                )
            }
            cache = list
            list
        } catch (e: Exception) {
            LogManager.e(TAG, "读取 languages.json 失败", e)
            emptyList()
        }
    }

    /** 清除内存缓存（文件更新后调用） */
    fun invalidateCache() { cache = null }

    // ── 获取 & 解析 ───────────────────────────────────────────────────────────

    /**
     * 从 GitHub API 获取 Linguist languages.yml，解析后保存为 languages.json。
     *
     * YAML 格式示例：
     * ```yaml
     * ABAP:
     *   type: programming
     *   color: "#E8274B"
     * ```
     *
     * 解析规则：
     *   - id = 小写 + 空格→连字符（撇号保留，与 GitHub 语言 slug 一致）
     *   - name = 原始键名
     *   - color = .color 字段（无则 null）
     *   - 排序：字母开头在前（按字母升序），数字开头在后（按字母升序）
     *
     * @return 成功解析的条目数，-1 表示失败
     */
    suspend fun fetchAndSave(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val token = ApiClient.currentToken()
                ?: return@withContext Result.failure(Exception("未登录，无法获取语言数据"))

            // 1. 首先调用 GitHub Contents API 获取 download_url
            val apiUrl = "https://api.github.com/repos/github-linguist/linguist/contents/lib/linguist/languages.yml"
            LogManager.d(TAG, "正在访问 API URL: $apiUrl")
            
            val apiRequest = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("X-GitHub-Api-Version", "2026-03-10")
                .build()

            val client = ApiClient.rawHttpClient()
            val apiResponse = client.newCall(apiRequest).execute()
            
            LogManager.d(TAG, "API HTTP 响应码: ${apiResponse.code}")
            
            if (!apiResponse.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${apiResponse.code}：${apiResponse.message}"))
            }
            
            val apiJson = apiResponse.body?.string()
                ?: return@withContext Result.failure(Exception("API 响应体为空"))
            
            LogManager.d(TAG, "API 响应 JSON: ${apiJson.take(500)}")
            
            val apiObj = JSONObject(apiJson)
            val downloadUrl = apiObj.getString("download_url")
            
            LogManager.d(TAG, "获取到 download_url: $downloadUrl")

            // 2. 使用 download_url 直接下载原始 YAML 文件
            LogManager.d(TAG, "正在下载原始 YAML 文件")
            
            val yamlRequest = Request.Builder()
                .url(downloadUrl)
                .build()
            
            val yamlResponse = client.newCall(yamlRequest).execute()
            
            LogManager.d(TAG, "YAML HTTP 响应码: ${yamlResponse.code}")
            
            if (!yamlResponse.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${yamlResponse.code}：${yamlResponse.message}"))
            }
            
            val yamlText = yamlResponse.body?.string()
                ?: return@withContext Result.failure(Exception("YAML 响应体为空"))

            LogManager.d(TAG, "原始 YAML 长度: ${yamlText.length}")
            LogManager.d(TAG, "原始 YAML 前500字符: ${yamlText.take(500)}")

            // 3. Jackson 解析 YAML → Map<String, Any>
            val yamlMapper = ObjectMapper(YAMLFactory())
            @Suppress("UNCHECKED_CAST")
            val root = yamlMapper.readValue(yamlText, Map::class.java) as Map<String, Any?>

            LogManager.d(TAG, "解析后根映射大小: ${root.size}")
            LogManager.d(TAG, "解析后前10个键: ${root.keys.take(10)}")

            // 4. 转换为 LanguageEntry 列表
            val entries = root.entries.mapNotNull { (rawName, value) ->
                val attrs = value as? Map<*, *> ?: return@mapNotNull null
                val color = attrs["color"] as? String
                val id = toLanguageId(rawName)
                LanguageEntry(id = id, name = rawName, color = color)
            }

            LogManager.d(TAG, "转换后条目数量: ${entries.size}")

            // 5. 排序：字母开头在前，数字开头在后；同组内按字母升序
            val sorted = entries.sortedWith(compareBy(
                { if (it.name.first().isDigit()) 1 else 0 },
                { it.name.lowercase() },
            ))

            LogManager.d(TAG, "排序后条目数量: ${sorted.size}")
            LogManager.d(TAG, "排序后前10个条目: ${sorted.take(10).map { it.name }}")

            // 6. 序列化为 JSON 保存
            val jsonArr = JSONArray()
            sorted.forEach { entry ->
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("name", entry.name)
                if (entry.color != null) obj.put("color", entry.color) else obj.put("color", JSONObject.NULL)
                jsonArr.put(obj)
            }
            val jsonText = jsonArr.toString()
            jsonFile(context).writeText(jsonText)

            LogManager.d(TAG, "生成的 JSON 长度: ${jsonText.length}")
            LogManager.d(TAG, "生成的 JSON 前500字符: ${jsonText.take(500)}")

            // 7. 刷新内存缓存
            invalidateCache()
            cache = sorted

            LogManager.i(TAG, "语言数据获取成功，共 ${sorted.size} 条")
            Result.success(sorted.size)

        } catch (e: Exception) {
            LogManager.e(TAG, "fetchAndSave 失败", e)
            Result.failure(e)
        }
    }

    /**
     * 将原始语言名称转换为 ID（与 GitHub Linguist slug 规则一致）：
     *   - 全部小写
     *   - 空格 → 连字符 `-`
     *   - 撇号 `'` 保留
     *   - 其余特殊字符保留（如 `.`、`+`、`#`、`*`）
     */
    fun toLanguageId(name: String): String =
        name.lowercase().replace(' ', '-')

    // ── 语言列表合并排序（UI 层使用）────────────────────────────────────────────

    /**
     * 构建语言选择列表：
     *   1. 当前已加载仓库中出现的语言排在最前（保持它们的字母顺序）
     *   2. 其余语言按 languages.json 的顺序（字母升序，数字结尾）
     *   3. 不重复
     *
     * @param context       应用 Context
     * @param repoLanguages 当前仓库列表中出现的语言名称集合（GHRepo.language 的值）
     */
    fun buildLanguageList(context: Context, repoLanguages: Set<String>): List<LanguageEntry> {
        val all = loadLanguages(context)
        if (all.isEmpty()) {
            // 没有本地数据：退化为仓库中出现的语言（按字母排序）
            return repoLanguages.sorted().map { name ->
                LanguageEntry(id = toLanguageId(name), name = name, color = null)
            }
        }
        // 已出现在仓库中的语言（按 name 匹配，保持 all 中的 color 信息）
        val inRepo = all.filter { it.name in repoLanguages }
        // 其余语言（不在仓库中，保持 all 顺序）
        val inRepoNames = inRepo.map { it.name }.toSet()
        val rest = all.filter { it.name !in inRepoNames }
        return inRepo + rest
    }
}
