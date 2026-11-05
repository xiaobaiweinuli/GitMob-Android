package com.gitmob.app.core.network

import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.data.model.AuthenticatedUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** PAT、设备授权和刷新共同验证候选 token，不读取活动账号或主页缓存。 */
@Singleton
class GitHubIdentityClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    @Named("restBaseUrl") private val baseUrl: String,
) {
    suspend fun verify(token: String): AuthenticatedUser {
        require(token.isNotBlank()) { "Credential must not be blank" }
        val request = Request.Builder().url("$baseUrl/user")
            .header("Authorization", "Bearer $token")
            .header("Accept", DEFAULT_ACCEPT)
            .header("X-GitHub-Api-Version", REST_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .cacheControl(CacheControl.FORCE_NETWORK).get().build()
        val text = client.newCall(request).readCancellable { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, "Identity request failed")
            response.body.string()
        }
        val user = try {
            json.decodeFromString<IdentityResponse>(text)
        } catch (_: SerializationException) {
            error("Identity response is invalid")
        }
        check(user.id > 0 && user.nodeId.isNotBlank() && user.login.isNotBlank()) {
            "Identity response is incomplete"
        }
        return AuthenticatedUser(user.id, user.nodeId, user.login, user.name, user.avatarUrl)
    }
}

@Serializable
private class IdentityResponse(
    val id: Long = 0,
    @SerialName("node_id") val nodeId: String = "",
    val login: String = "",
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
