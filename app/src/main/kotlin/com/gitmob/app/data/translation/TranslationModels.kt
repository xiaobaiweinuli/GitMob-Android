package com.gitmob.app.data.translation

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationProvider { EDGE, TRANSMART, BAIDU, YOUDAO_OPEN, CUSTOM }
@Serializable
enum class TranslationAuth { NONE, BEARER, BODY_KEY, HEADER }

@Serializable
data class TranslationPreferences(
    val enabled: Boolean = false,
    val provider: TranslationProvider = TranslationProvider.EDGE,
    val target: String = "zh-CN",
    val source: String = "en",
    val bilingual: Boolean = true,
)

@Serializable
data class TranslationCredentials(
    val appId: String = "",
    val secret: String = "",
    val endpoint: String = "",
    val method: String = "POST",
    val auth: TranslationAuth = TranslationAuth.NONE,
    val header: String = "X-API-Key",
    val template: String = "{\"q\":\"{{text}}\",\"source\":\"{{source}}\",\"target\":\"{{target}}\",\"format\":\"text\"}",
    val responsePath: String = "translatedText",
)

data class TranslationConfiguration(
    val preferences: TranslationPreferences = TranslationPreferences(),
    val credentials: Map<TranslationProvider, TranslationCredentials> = emptyMap(),
    val storageFailed: Boolean = false,
) {
    fun credentialsFor(provider: TranslationProvider) = credentials[provider] ?: TranslationCredentials()
}

enum class TranslationProblem { NETWORK, AUTH, LIMIT, QUOTA, LANGUAGE, CONFIGURATION, RESPONSE, MAPPING, TOO_LONG, EMPTY, STORAGE, DISPLAY }
class TranslationException(
    val problem: TranslationProblem,
    val httpStatus: Int? = null,
    val serviceCode: String? = null,
    val retryAfterMillis: Long? = null,
) : Exception(problem.name)

data class TranslationTarget(val key: String, val content: String, val html: Boolean = false) {
    val identity: String by lazy { key + ":" + java.security.MessageDigest.getInstance("SHA-256")
        .digest((html.toString() + content).toByteArray()).joinToString("") { "%02x".format(it) } }
}

/** Messages contain no credentials or response bodies. Results identify original display units. */
sealed interface TranslationUpdate {
    data class Batch(val values: Map<Int, String>) : TranslationUpdate
    data class Waiting(val untilMillis: Long) : TranslationUpdate
    data object RequestStarted : TranslationUpdate
}
