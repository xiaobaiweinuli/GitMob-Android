package com.gitmob.app.core.network

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.TestAccessTokenProvider
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaTransportTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var external: MockWebServer
    private lateinit var api: GHApiClient
    private lateinit var client: OkHttpClient
    private var token = "test-token"
    private var tokenReads = 0
    private val rejected = mutableListOf<String>()
    private val path = "/repos/owner/repo/contents/movie.mp4?ref=main"

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        external = MockWebServer().apply { start() }
        api = GHApiClient(OkHttpClient(), Json, object : TestAccessTokenProvider {
            override suspend fun getToken(): String {
                assertSame(accounts.session.epoch, AccountContext.ensureCurrent())
                tokenReads++
                return token
            }
            override suspend fun onUnauthorized(token: String, epoch: AccountEpoch) {
                assertSame(accounts.session.epoch, epoch)
                rejected += token
            }
        }, server.url("/").toString().removeSuffix("/"))
        client = api.mediaClient(accounts.session.epoch)
    }

    @After fun cleanup() {
        client.dispatcher.cancelAll()
        server.close()
        external.close()
    }

    @Test fun streamingPreservesRangeAndSkipsBytesWhenTheServerReturnsTheWholeFile() {
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "video/mp4")
            .body("0123456789").build())
        val source = OkHttpDataSource.Factory(client).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(server.url(path).toString()).setPosition(4).setLength(4).build())
            val bytes = ByteArray(4)
            assertEquals(4, source.read(bytes, 0, bytes.size))
            assertEquals("4567", bytes.decodeToString())
            assertEquals(-1, source.read(bytes, 0, bytes.size))
        } finally { source.close() }
        val request = server.takeRequest()
        assertEquals("bytes=4-7", request.headers["Range"])
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Accept"])
        assertNull(request.headers["X-GitHub-Api-Version"])
        assertEquals(1, server.requestCount)
    }

    @Test fun partialResponsesAreReadWithoutSkippingTwice() {
        server.enqueue(MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 4-7/10").body("4567").build())
        val source = OkHttpDataSource.Factory(client).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(server.url(path).toString()).setPosition(4).setLength(4).build())
            val bytes = ByteArray(4)
            assertEquals(4, source.read(bytes, 0, bytes.size))
            assertEquals("4567", bytes.decodeToString())
        } finally { source.close() }
    }

    @Test fun crossOriginRedirectsDropAccountAndGithubHeaders() {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", external.url(path)).build())
        external.enqueue(MockResponse(body = "video"))
        client.newCall(Request.Builder().url(server.url(path)).header("Range", "bytes=0-").header("Authorization", "Bearer must-not-leak")
            .header("X-GitHub-Api-Version", REST_API_VERSION).header("Accept", "application/vnd.github.raw+json").build()).execute().use {
            assertEquals("video", it.body.string())
        }
        assertNull(server.takeRequest().headers["Authorization"])
        val request = external.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["X-GitHub-Api-Version"])
        assertNull(request.headers["Accept"])
        assertEquals("bytes=0-", request.headers["Range"])
        assertEquals(0, tokenReads)
    }

    @Test fun externalMediaDoesNotReadCredentialsEvenWithAnApiLikePath() {
        external.enqueue(MockResponse(code = 401))
        client.newCall(Request.Builder().url(external.url(path)).build()).execute().use { assertEquals(401, it.code) }
        val request = external.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["X-GitHub-Api-Version"])
        assertNull(request.headers["Accept"])
        assertEquals(0, tokenReads)
        assertTrue(rejected.isEmpty())
    }

    @Test fun mediaUnauthorizedDoesNotInvalidateTheGithubAccount() {
        server.enqueue(MockResponse(code = 401))
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { assertEquals(401, it.code) }
        assertTrue(rejected.isEmpty())
        assertEquals(0, tokenReads)
        assertEquals(1, server.requestCount)
    }

    @Test fun stalePlaybackCannotSendRequestsWithAnotherAccountsCredentials() {
        accounts.session.replace(emptyList(), null)
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, tokenReads)
    }
}
