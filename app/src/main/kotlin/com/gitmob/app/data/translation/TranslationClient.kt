package com.gitmob.app.data.translation

import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Separate transport: no GitHub headers, cookies, logging or credential-bearing redirects. */
@Singleton
class TranslationClient @Inject constructor(private val json: Json) {
    private val client = OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(30))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun translate(text: String, preferences: TranslationPreferences, credentials: TranslationCredentials): String =
        translateBatch(listOf(text), preferences, credentials).single()

    suspend fun translateBatch(texts: List<String>, preferences: TranslationPreferences, credentials: TranslationCredentials): List<String> = withContext(Dispatchers.IO) {
        try {
            val data = json.parseToJsonElement(execute(buildBatchRequest(texts, preferences, credentials)))
            val translated = parseBatchResponse(preferences.provider, data, credentials.responsePath, texts.size)
            if (translated.any(String::isBlank)) throw TranslationException(TranslationProblem.EMPTY)
            translated
        } catch (e: CancellationException) { throw e
        } catch (e: TranslationException) { throw e
        } catch (_: IOException) { throw TranslationException(TranslationProblem.NETWORK)
        } catch (_: Exception) { throw TranslationException(TranslationProblem.RESPONSE) }
    }

    internal suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(TranslationException(TranslationProblem.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw TranslationException(when (it.code) {
                            401, 403 -> TranslationProblem.AUTH
                            429 -> TranslationProblem.LIMIT
                            413 -> TranslationProblem.TOO_LONG
                            else -> TranslationProblem.NETWORK
                        }, httpStatus = it.code, retryAfterMillis = retryAfter(it.header("Retry-After")))
                        val body = it.body
                        val source = body.source()
                        if (source.request(2L * 1024 * 1024 + 1)) throw TranslationException(TranslationProblem.TOO_LONG)
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (e is TranslationException) e else TranslationException(TranslationProblem.NETWORK))
                }
            }
        })
    }

    internal fun buildRequest(text: String, p: TranslationPreferences, c: TranslationCredentials, salt: String = UUID.randomUUID().toString(), time: String = (System.currentTimeMillis() / 1000).toString()): Request =
        buildBatchRequest(listOf(text), p, c, salt, time)

    internal fun buildBatchRequest(texts: List<String>, p: TranslationPreferences, c: TranslationCredentials, salt: String = UUID.randomUUID().toString(), time: String = (System.currentTimeMillis() / 1000).toString()): Request {
        if (texts.isEmpty() || (!supportsBatch(p.provider) && texts.size != 1)) throw TranslationException(TranslationProblem.CONFIGURATION)
        val text = texts.first()
        val target = language(p.provider, p.target)
        fun form(url: String, fields: Map<String, String>) = Request.Builder().url(url)
            .post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()).build()
        fun post(url: String, value: JsonElement) = Request.Builder().url(url).post(value.toString().toRequestBody(JSON)).build()
        return when (p.provider) {
            TranslationProvider.BAIDU -> {
                requireKey(c)
                form("https://fanyi-api.baidu.com/api/trans/vip/translate", mapOf(
                    "q" to text, "from" to "auto", "to" to target, "appid" to c.appId.trim(), "salt" to salt,
                    "sign" to digest("MD5", c.appId.trim() + text + salt + c.secret),
                ))
            }
            TranslationProvider.YOUDAO_OPEN -> {
                requireKey(c)
                form("https://openapi.youdao.com/api", mapOf(
                    "q" to text, "from" to "auto", "to" to target, "appKey" to c.appId.trim(), "salt" to salt,
                    "curtime" to time, "signType" to "v3", "sign" to digest("SHA-256", c.appId.trim() + youdaoInput(text) + salt + time + c.secret),
                ))
            }
            TranslationProvider.CUSTOM -> customRequest(text, p, c)
            TranslationProvider.EDGE -> post("https://edge.microsoft.com/translate/translatetext?from=&to=$target&isEnterpriseClient=false", JsonArray(texts.map(::JsonPrimitive)))
            TranslationProvider.TRANSMART -> post("https://transmart.qq.com/api/imt", buildJsonObject {
                putJsonObject("header") { put("fn", "auto_translation"); put("session", ""); put("user", ""); put("client_key", "browser-chrome-120.0-Windows_10-$salt") }
                put("type", "plain"); put("model_category", "normal"); put("text_domain", "general")
                putJsonObject("source") { put("lang", "auto"); put("text_list", JsonArray(texts.map(::JsonPrimitive))) }
                putJsonObject("target") { put("lang", target) }
            })
        }
    }

    internal fun requestBytes(texts: List<String>, p: TranslationPreferences, c: TranslationCredentials): Long =
        buildBatchRequest(texts, p, c, salt = "00000000-0000-0000-0000-000000000000").body?.contentLength() ?: 0L

    internal fun customRequest(text: String, p: TranslationPreferences, c: TranslationCredentials): Request {
        try {
            val url = c.endpoint.trim().toHttpUrlOrNull() ?: throw TranslationException(TranslationProblem.CONFIGURATION)
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || c.responsePath.isBlank()) throw TranslationException(TranslationProblem.CONFIGURATION)
            if (c.method !in setOf("GET", "POST")) throw TranslationException(TranslationProblem.CONFIGURATION)
            if (c.auth != TranslationAuth.NONE && c.secret.isBlank()) throw TranslationException(TranslationProblem.CONFIGURATION)
            val template = json.parseToJsonElement(c.template)
            if (template !is JsonObject || !c.template.contains("{{text}}")) throw TranslationException(TranslationProblem.CONFIGURATION)
            val values = replacePlaceholders(template, mapOf("{{text}}" to text, "{{source}}" to p.source, "{{target}}" to p.target)).jsonObject.toMutableMap()
            if (c.auth == TranslationAuth.BODY_KEY) values["api_key"] = JsonPrimitive(c.secret)
            val request = Request.Builder()
            if (c.method == "GET") {
                val builder = url.newBuilder()
                values.forEach { (key, value) -> builder.addQueryParameter(key, (value as? JsonPrimitive)?.content ?: value.toString()) }
                request.url(builder.build())
            } else request.url(url).post(JsonObject(values).toString().toRequestBody(JSON))
            when (c.auth) {
                TranslationAuth.BEARER -> request.header("Authorization", "Bearer ${c.secret}")
                TranslationAuth.HEADER -> {
                    if (c.header.lowercase() in setOf("host", "content-length", "connection", "cookie", "proxy-authorization") || c.header.isBlank()) throw TranslationException(TranslationProblem.CONFIGURATION)
                    request.header(c.header, c.secret)
                }
                else -> Unit
            }
            return request.build()
        } catch (e: TranslationException) { throw e
        } catch (_: Exception) { throw TranslationException(TranslationProblem.CONFIGURATION) }
    }

    internal fun parseResponse(provider: TranslationProvider, data: JsonElement, path: String): String {
        fun field(key: String) = (data as? JsonObject)?.get(key)
        fun text(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun arrayText(value: JsonElement?) = (value as? JsonArray)?.mapNotNull(::text)?.joinToString("\n")
        when (provider) {
            TranslationProvider.YOUDAO_OPEN -> {
                val error = (field("errorCode") as? JsonPrimitive)?.content
                if (error != null && error != "0") throw TranslationException(when (error) {
                    "411", "412", "429" -> TranslationProblem.LIMIT
                    "401" -> TranslationProblem.QUOTA
                    "101", "108", "110", "202" -> TranslationProblem.AUTH
                    "102" -> TranslationProblem.LANGUAGE
                    "103" -> TranslationProblem.TOO_LONG
                    else -> TranslationProblem.RESPONSE
                }, serviceCode = error)
                return arrayText(field("translation")) ?: throw TranslationException(TranslationProblem.RESPONSE)
            }
            TranslationProvider.BAIDU -> {
                val error = (field("error_code") as? JsonPrimitive)?.content
                if (error != null && error != "52000") throw TranslationException(when (error) {
                    "54003", "54005" -> TranslationProblem.LIMIT
                    "54004" -> TranslationProblem.QUOTA
                    "52003", "54001", "58000" -> TranslationProblem.AUTH
                    "58001" -> TranslationProblem.LANGUAGE
                    else -> TranslationProblem.RESPONSE
                }, serviceCode = error)
                return (field("trans_result") as? JsonArray)?.joinToString("\n") {
                    text((it as? JsonObject)?.get("dst")) ?: throw TranslationException(TranslationProblem.RESPONSE)
                } ?: throw TranslationException(TranslationProblem.RESPONSE)
            }
            TranslationProvider.CUSTOM -> {
                var value: JsonElement? = data
                path.replace("[", ".").replace("]", "").split('.').filter(String::isNotEmpty).forEach { key ->
                    value = when (val current = value) {
                        is JsonObject -> current[key]
                        is JsonArray -> key.toIntOrNull()?.let(current::getOrNull)
                        else -> null
                    }
                }
                return text(value) ?: throw TranslationException(TranslationProblem.RESPONSE)
            }
            TranslationProvider.EDGE, TranslationProvider.TRANSMART -> return parseBatchResponse(provider, data, path, 1).single()
        }
    }

    internal fun parseBatchResponse(provider: TranslationProvider, data: JsonElement, path: String, expected: Int): List<String> {
        if (!supportsBatch(provider)) {
            if (expected != 1) throw TranslationException(TranslationProblem.MAPPING)
            return listOf(parseResponse(provider, data, path))
        }
        val items = when (provider) {
            TranslationProvider.EDGE -> data as? JsonArray
            TranslationProvider.TRANSMART -> (data as? JsonObject)?.get("auto_translation") as? JsonArray
            else -> null
        } ?: throw TranslationException(TranslationProblem.RESPONSE)
        if (items.size != expected) throw TranslationException(TranslationProblem.MAPPING)
        return items.map { item ->
            val value = if (item is JsonObject && provider == TranslationProvider.EDGE) {
                (((item["translations"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text"))
            } else item
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw TranslationException(TranslationProblem.MAPPING)
        }
    }

    private fun requireKey(c: TranslationCredentials) { if (c.appId.isBlank() || c.secret.isBlank()) throw TranslationException(TranslationProblem.CONFIGURATION) }

    companion object {
        internal fun supportsBatch(provider: TranslationProvider) = provider == TranslationProvider.EDGE || provider == TranslationProvider.TRANSMART
        internal fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
            if (value == null) return null
            value.trim().toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
            return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull()
        }
        private val JSON = "application/json; charset=utf-8".toMediaType()
        internal fun digest(algorithm: String, value: String): String = MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        internal fun youdaoInput(text: String): String {
            val points = text.codePoints().toArray()
            return if (points.size <= 20) text else String(points, 0, 10) + points.size + String(points, points.size - 10, 10)
        }
        internal fun replacePlaceholders(value: JsonElement, replacements: Map<String, String>): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { replacePlaceholders(it.value, replacements) })
            is JsonArray -> JsonArray(value.map { replacePlaceholders(it, replacements) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(Regex("\\{\\{(?:text|source|target)\\}\\}").replace(value.content) { replacements[it.value] ?: it.value }) else value
        }
        internal fun language(provider: TranslationProvider, value: String): String = when (provider) {
            TranslationProvider.YOUDAO_OPEN -> when (value) { "zh-CN" -> "zh-CHS"; "zh-TW" -> "zh-CHT"; else -> value }
            TranslationProvider.BAIDU -> mapOf("zh-CN" to "zh", "zh-TW" to "cht", "ja" to "jp", "ko" to "kor", "fr" to "fra", "es" to "spa").getOrDefault(value, value)
            TranslationProvider.EDGE -> when (value) { "zh-CN" -> "zh-Hans"; "zh-TW" -> "zh-Hant"; else -> value }
            TranslationProvider.TRANSMART -> if (value == "zh-CN") "zh" else value
            else -> value
        }
    }
}
