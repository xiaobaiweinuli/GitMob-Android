package com.gitmob.app.core.auth

import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.network.USER_AGENT
import com.gitmob.app.core.network.readCancellable
import com.gitmob.app.data.model.AccountAuthMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

const val GITHUB_CLIENT_ID = "Ov23liP9mC2HXALHsFpk"
const val GITHUB_DEVICE_SCOPE = "repo user gist read:org notifications delete_repo workflow"

/** OAuth 不带活动账号的 Authorization，也不混入 REST 版本或媒体类型头。 */
@Singleton
class GitHubOAuthClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    @Named("oauthBaseUrl") private val baseUrl: String,
) {
    suspend fun startDeviceFlow(): DeviceCodeResponse = decode(
        post("/login/device/code", mapOf("client_id" to GITHUB_CLIENT_ID, "scope" to GITHUB_DEVICE_SCOPE)),
    )

    suspend fun poll(deviceCode: String): OAuthTokenResponse = decode(
        post("/login/oauth/access_token", mapOf(
            "client_id" to GITHUB_CLIENT_ID,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )),
    )

    suspend fun refresh(refreshToken: String): OAuthTokenResponse = decode(
        post("/login/oauth/access_token", mapOf(
            "client_id" to GITHUB_CLIENT_ID,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
        )),
    )

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString(text)
    } catch (_: SerializationException) {
        // 解码异常可能包含响应片段；凭据响应不能进入日志。
        error("OAuth response is invalid")
    }

    private suspend fun post(path: String, fields: Map<String, String>): String {
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url("$baseUrl$path")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body).build()
        return client.newCall(request).readCancellable { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw HttpStatusException(response.code, "OAuth request failed")
            text
        }
    }
}

@Serializable
class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val interval: Long = 5,
    val error: String? = null,
)

@Serializable
class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token_expires_in") val refreshExpiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String = "",
    val error: String? = null,
    val interval: Long? = null,
) {
    fun credentials(now: Long = System.currentTimeMillis()): AccountCredentials {
        check(error == null && accessToken.isNotBlank() && tokenType.equals("bearer", true)) {
            "OAuth token response is incomplete"
        }
        fun expiry(seconds: Long?): Long? = seconds?.let {
            check(it > 0 && it <= (Long.MAX_VALUE - now) / 1000) { "Invalid OAuth expiry" }
            now + it * 1000
        }
        return AccountCredentials(accessToken, refreshToken?.takeIf(String::isNotBlank),
            expiry(expiresIn), expiry(refreshExpiresIn), AccountAuthMethod.DEVICE_FLOW)
    }
}
