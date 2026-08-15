package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.InboxReadFilter
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
    fun `getNotifications parses response and returns source page metadata`() = runTest {
        server.enqueue(MockResponse().setBody(notificationArray(id = "1", unread = true)))

        val result = repository.getNotifications()

        assertTrue(result is ApiResult.Success)
        val page = (result as ApiResult.Success).data
        assertEquals(1, page.items.size)
        assertEquals("mention", page.items.first().reason)
        assertEquals(2, page.nextSourcePage)
        assertTrue(!page.hasNextPage)
        assertEquals("/notifications?all=false&page=1&per_page=30", server.takeRequest().path)
    }

    @Test
    fun `READ filter scans full source pages until read items are collected`() = runTest {
        server.enqueue(MockResponse().setBody(notificationArray(30, unread = true)))
        server.enqueue(MockResponse().setBody(notificationArray(id = "read-1", unread = false)))

        val result = repository.getNotifications(filter = InboxReadFilter.READ)

        assertTrue(result is ApiResult.Success)
        val page = (result as ApiResult.Success).data
        assertEquals(listOf("read-1"), page.items.map { it.id })
        assertEquals(3, page.nextSourcePage)
        assertTrue(!page.hasNextPage)
        assertEquals("/notifications?all=true&page=1&per_page=30", server.takeRequest().path)
        assertEquals("/notifications?all=true&page=2&per_page=30", server.takeRequest().path)
    }

    @Test
    fun `ALL filter requests all notifications`() = runTest {
        server.enqueue(MockResponse().setBody(notificationArray(id = "1", unread = false)))

        val result = repository.getNotifications(filter = InboxReadFilter.ALL)

        assertTrue(result is ApiResult.Success)
        assertEquals("/notifications?all=true&page=1&per_page=30", server.takeRequest().path)
    }

    @Test
    fun `markAsRead handles empty 205 response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(205))

        val result = repository.markAsRead("thread-1")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `markAllAsRead uses PUT without request body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = repository.markAllAsRead()

        assertTrue(result is ApiResult.Success)
        assertEquals("PUT", server.takeRequest().method)
    }

    private fun notificationJson(id: String, unread: Boolean): String =
        """
        {
            "id":"$id",
            "repository":{"name":"repo","owner":{"login":"octocat"}},
            "subject":{"title":"Title $id","url":"https://api.github.com/repos/octocat/repo/issues/5","type":"Issue"},
            "reason":"mention",
            "unread":$unread,
            "updated_at":"2026-01-01T00:00:00Z"
        }
        """.trimIndent()

    private fun notificationArray(count: Int, unread: Boolean): String =
        (0 until count).joinToString(prefix = "[", postfix = "]") {
            notificationJson(id = "item-$it", unread = unread)
        }

    private fun notificationArray(id: String, unread: Boolean): String =
        "[${notificationJson(id, unread)}]"
}
