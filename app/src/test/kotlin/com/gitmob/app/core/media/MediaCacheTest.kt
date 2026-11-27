package com.gitmob.app.core.media

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.data.model.DocumentResourceKind
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.model.MediaSource
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaCacheTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val files = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var cache: MediaCache
    private lateinit var server: MockWebServer
    private val upstream = OkHttpDataSource.Factory(OkHttpClient())

    @Before fun setup() {
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCacheDir() = files.root
        }
        cache = MediaCache(context)
        server = MockWebServer().apply { start() }
    }

    @After fun cleanup() {
        cache.diskCache.release()
        server.close()
    }

    private fun media(version: String? = "repo/path#sha1", token: String = "first") =
        MediaSource(server.url("/movie.mp4?token=$token").toString(), DocumentResourceKind.VIDEO, cacheKey = version)

    private suspend fun factory(source: MediaSource) = cache.dataSourceFactory(upstream, source, accounts.session.epoch)

    private fun read(factory: DataSource.Factory, source: MediaSource, position: Long, length: Long): String {
        val data = factory.createDataSource()
        try {
            data.open(DataSpec.Builder().setUri(source.url).setPosition(position).setLength(length).build())
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(2)
            while (true) {
                val count = data.read(buffer, 0, buffer.size)
                if (count == -1) break
                result.write(buffer, 0, count)
            }
            return result.toByteArray().decodeToString()
        } finally { data.close() }
    }

    private fun partial(body: String, start: Int, end: Int) {
        server.enqueue(MockResponse.Builder().code(206)
            .setHeader("Content-Range", "bytes $start-$end/10").body(body).build())
    }

    @Test fun cachedBytesSurvivePlayerReopenAndSignedUrlRenewal() = runBlocking {
        val source = media()
        partial("4567", 4, 7)
        assertEquals("4567", read(factory(source), source, 4, 4))
        assertEquals("bytes=4-7", server.takeRequest().headers["Range"])
        val renewed = media(token = "renewed")
        assertEquals("4567", read(factory(renewed), renewed, 4, 4))
        assertEquals(1, server.requestCount)
        assertEquals(4L, cache.diskCache.cacheSpace)
    }

    @Test fun seekingAcrossACachedBoundaryRequestsOnlyTheMissingRange() = runBlocking {
        val source = media()
        val factory = factory(source)
        partial("0123", 0, 3)
        assertEquals("0123", read(factory, source, 0, 4))
        assertEquals("bytes=0-3", server.takeRequest().headers["Range"])
        partial("4567", 4, 7)
        assertEquals("234567", read(factory, source, 2, 6))
        assertEquals("bytes=4-7", server.takeRequest().headers["Range"])
        assertEquals("01234567", read(factory, source, 0, 8))
        assertEquals(2, server.requestCount)
    }

    @Test fun newContentVersionsAndAccountsCannotReusePreviousBytes() = runBlocking {
        val first = media()
        partial("old!", 0, 3)
        assertEquals("old!", read(factory(first), first, 0, 4))
        val next = media(version = "repo/path#sha2")
        partial("new!", 0, 3)
        assertEquals("new!", read(factory(next), next, 0, 4))
        val other = GitHubAccount("2", 2, "U_2", "other")
        accounts.session.replace(listOf(other), other.key)
        partial("next", 0, 3)
        assertEquals("next", read(factory(next), next, 0, 4))
        assertEquals(3, server.requestCount)
    }

    @Test fun accountChangesBlockBothNewCacheOpensAndAlreadyOpenCacheReads() = runBlocking {
        val source = media()
        val factory = factory(source)
        partial("0123", 0, 3)
        read(factory, source, 0, 4)
        val open = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(source.url).setLength(4).build()
        try {
            open.open(spec)
            assertEquals(1, open.read(ByteArray(1), 0, 1))
            accounts.session.replace(emptyList(), null)
            assertThrows(IOException::class.java) { open.read(ByteArray(1), 0, 1) }
            assertThrows(IOException::class.java) { read(factory, source, 0, 4) }
        } finally { open.close() }
        assertEquals(1, server.requestCount)
    }

    @Test fun unversionedMediaReusesBytesOnlyWithinThePlaybackSession() = runBlocking {
        val source = media(version = null)
        val factory = factory(source)
        partial("old!", 0, 3)
        assertEquals("old!", read(factory, source, 0, 4))
        assertEquals("old!", read(factory, source, 0, 4))
        partial("new!", 0, 3)
        assertEquals("new!", read(factory(source), source, 0, 4))
        assertEquals(2, server.requestCount)
    }

    @Test fun cachedBytesPersistAfterReopeningTheDiskCache() = runBlocking {
        val source = media()
        partial("0123", 0, 3)
        read(factory(source), source, 0, 4)
        cache.diskCache.release()
        cache = MediaCache(context)
        assertEquals("0123", read(factory(source), source, 0, 4))
        assertEquals(1, server.requestCount)
    }
}
