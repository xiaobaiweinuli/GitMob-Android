package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: NotificationRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val api = GHApiClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            tokenProvider = object : AccessTokenProvider {
                override suspend fun getToken() = "fake-token"
            },
            restBaseUrl = baseUrl,
            graphQLUrl = "$baseUrl/graphql",
        )
        repository = NotificationRepository(api)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `getNotifications解析snake_case字段`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [{
                    "id":"1",
                    "repository":{"name":"repo","owner":{"login":"octocat"}},
                    "subject":{"title":"标题","url":"https://api.github.com/repos/octocat/repo/issues/5","type":"Issue"},
                    "reason":"mention",
                    "unread":true,
                    "updated_at":"2026-01-01T00:00:00Z"
                }]
                """.trimIndent(),
            ),
        )

        val result = repository.getNotifications()

        assertTrue(result is ApiResult.Success)
        val list = (result as ApiResult.Success).data
        assertEquals(1, list.size)
        assertEquals("mention", list.first().reason)
        assertTrue(list.first().isUnread)
    }

    @Test
    fun `markAsRead处理204空响应体不报错`() = runTest {
        server.enqueue(MockResponse().setResponseCode(205))

        val result = repository.markAsRead("thread-1")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `markAllAsRead走PUT且不需要请求体`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = repository.markAllAsRead()

        assertTrue(result is ApiResult.Success)
        assertEquals("PUT", server.takeRequest().method)
    }
}
