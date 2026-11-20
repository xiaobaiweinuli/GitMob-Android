package com.gitmob.app.core.network

import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubIdentityClientTest {
    @Test
    fun `身份请求区分拒绝限流服务和无效响应`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val client = GitHubIdentityClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, server.url("/").toString().removeSuffix("/"))
            val cases = listOf(
                MockResponse(code = 401) to ApiError.Unauthorized,
                MockResponse(code = 403) to ApiError.Forbidden,
                MockResponse.Builder().code(403).setHeader("X-RateLimit-Remaining", "0").build() to ApiError.RateLimited,
                MockResponse.Builder().code(403).setHeader("Retry-After", "20").build() to ApiError.RateLimited,
                MockResponse(code = 429) to ApiError.RateLimited,
                MockResponse(code = 503) to ApiError.UserVisible(R.string.auth_service_unavailable),
                MockResponse(body = "{invalid secret-token") to ApiError.UserVisible(R.string.auth_response_invalid),
                MockResponse(body = "{}") to ApiError.UserVisible(R.string.auth_response_invalid),
                MockResponse(body = "null") to ApiError.UserVisible(R.string.auth_response_invalid),
                MockResponse(body = """{"id":1,"login":"user"}""") to ApiError.UserVisible(R.string.auth_response_invalid),
                MockResponse(body = """{"id":0,"node_id":"U_1","login":"user"}""") to ApiError.UserVisible(R.string.auth_response_invalid),
            )
            for ((response, expected) in cases) {
                server.enqueue(response)
                assertEquals(ApiResult.Failure(expected), safeCall { client.verify("candidate") })
            }
            server.enqueue(MockResponse(body = """{"id":1,"node_id":"U_1","login":"user","extra":true}"""))
            assertEquals("user", client.verify("candidate").login)
        } finally { server.close() }
    }
}
