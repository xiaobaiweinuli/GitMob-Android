package com.gitmob.app.core.auth

import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubOAuthClientTest {
    @Test
    fun `非成功HTTP保留OAuth错误且限流服务状态优先`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val client = GitHubOAuthClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, server.url("/").toString().removeSuffix("/"))
            for (status in listOf(200, 400, 401, 403)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"incorrect_client_credentials","error_description":"secret"}"""))
                val code = client.startDeviceFlow()
                assertEquals("incorrect_client_credentials", code.error)
                assertEquals(R.string.auth_oauth_config_invalid, oauthErrorMessageRes(code.error!!))
                val request = server.takeRequest()
                assertEquals("/login/device/code", request.path)
                assertEquals(null, request.getHeader("Authorization"))
                assertEquals(null, request.getHeader("X-GitHub-Api-Version"))
            }
            val cases = listOf(
                MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0") to ApiError.RateLimited,
                MockResponse().setResponseCode(403).setHeader("Retry-After", "10") to ApiError.RateLimited,
                MockResponse().setResponseCode(429) to ApiError.RateLimited,
                MockResponse().setResponseCode(503) to ApiError.UserVisible(R.string.auth_service_unavailable),
                MockResponse().setResponseCode(403) to ApiError.Forbidden,
                MockResponse().setResponseCode(401) to ApiError.Unauthorized,
            )
            for ((response, expected) in cases) {
                // 普通 401/403 不含有效 OAuth 错误时保留 HTTP 分类。
                val body = if (expected == ApiError.Unauthorized || expected == ApiError.Forbidden) "{}"
                    else """{"error":"access_denied"}"""
                server.enqueue(response.setBody(body))
                assertEquals(ApiResult.Failure(expected), safeCall { client.poll("device-code") })
            }
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad_refresh_token"}"""))
            assertEquals("bad_refresh_token", client.refresh("old-refresh").error)
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"expired_token"}"""))
            assertEquals("expired_token", client.poll("device-code").error)
            server.enqueue(MockResponse().setBody("{invalid private-response"))
            assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_response_invalid)), safeCall { client.poll("device-code") })
        } finally { server.shutdown() }
    }

    @Test
    fun `凭据响应区分OAuth业务错误和缺失字段`() = runTest {
        val invalid = ApiResult.Failure(ApiError.UserVisible(R.string.auth_response_invalid))
        for (response in listOf(
            OAuthTokenResponse(),
            OAuthTokenResponse(accessToken = "secret", tokenType = "basic"),
            OAuthTokenResponse(accessToken = "secret", tokenType = "bearer", expiresIn = 0),
            OAuthTokenResponse(accessToken = "secret", tokenType = "bearer", refreshExpiresIn = Long.MAX_VALUE),
        )) assertEquals(invalid, safeCall { response.credentials(now = 1000) })
        for ((error, message) in listOf(
            "expired_token" to R.string.auth_device_expired,
            "access_denied" to R.string.auth_device_denied,
            "incorrect_client_credentials" to R.string.auth_oauth_config_invalid,
            "unsupported_grant_type" to R.string.auth_oauth_config_invalid,
            "unrecognized secret" to R.string.auth_oauth_failed,
        )) assertEquals(ApiResult.Failure(ApiError.UserVisible(message)), safeCall { OAuthTokenResponse(error = error).credentials() })
        val credentials = OAuthTokenResponse(accessToken = "access", refreshToken = "refresh", tokenType = "Bearer", expiresIn = 2).credentials(now = 1000)
        assertEquals("access", credentials.accessToken)
        assertEquals(3000L, credentials.expiresAtMillis)
    }
}
