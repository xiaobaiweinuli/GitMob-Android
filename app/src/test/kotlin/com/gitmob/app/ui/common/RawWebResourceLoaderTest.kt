package com.gitmob.app.ui.common

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import com.gitmob.app.core.network.RawAccelerationInterceptor
import com.gitmob.app.core.network.RawUrlPolicy
import com.gitmob.app.core.preferences.*
import com.gitmob.app.testutil.NetworkPreferencesFixture
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RawWebResourceLoaderTest {
    private val policy = RawUrlPolicy()
    private val preferences = RawAccelerationPreferenceStore(NetworkPreferencesFixture())
    private val raw = "https://raw.githubusercontent.com/o/r/main/image.svg"
    private var captured: Request? = null
    private var closed = false
    private fun request(url: String = raw, headers: Map<String, String> = emptyMap()): WebResourceRequest = mockk {
        every { this@mockk.url } returns Uri.parse(url)
        every { method } returns "GET"
        every { requestHeaders } returns headers
    }
    private fun loader(): RawWebResourceLoader {
        val client = OkHttpClient.Builder().addInterceptor(RawAccelerationInterceptor(preferences, policy)).addInterceptor { chain ->
            captured = chain.request()
            val body = object : ResponseBody() {
                private val bytes = Buffer().writeUtf8("<svg/>")
                override fun contentType() = "image/svg+xml; charset=utf-8".toMediaType()
                override fun contentLength() = 6L
                override fun source(): BufferedSource = bytes
                override fun close() { closed = true; super.close() }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .header("Content-Range", "bytes 0-5/6").header("Set-Cookie", "proxy=secret").body(body).build()
        }.build()
        return RawWebResourceLoader.Factory(client, preferences, policy).create()
    }
    private suspend fun enabled() {
        preferences.update { it.copy(services = listOf(RawAccelerationService("a", "Proxy", "https://proxy.example/")), selectedId = "a", enabled = true) }
    }

    @Test fun forwardsRangeAndMimeStreamsBodyAndClosesTransport() = runTest {
        enabled()
        val loader = loader()
        val response = loader.intercept(request(headers = mapOf("Range" to "bytes=0-5", "Referer" to "private", "If-None-Match" to "old")))!!
        assertEquals("https://proxy.example/$raw", captured!!.url.toString())
        assertEquals("bytes=0-5", captured!!.header("Range"))
        assertNull(captured!!.header("Referer"))
        assertNull(captured!!.header("If-None-Match"))
        assertEquals(206, response.statusCode)
        assertEquals("image/svg+xml", response.mimeType)
        assertEquals("UTF-8", response.encoding)
        assertEquals("bytes 0-5/6", response.responseHeaders.entries.first { it.key.equals("Content-Range", true) }.value)
        assertTrue(response.responseHeaders.keys.none { it.equals("Set-Cookie", true) })
        assertEquals("<svg/>", response.data.bufferedReader().readText())
        response.data.close()
        assertTrue(closed)
        loader.close()
    }

    @Test fun disabledSignedAuthenticatedAndUnrelatedResourcesStayWithExistingLoader() = runTest {
        val loader = loader()
        assertNull(loader.intercept(request()))
        enabled()
        assertNull(loader.intercept(request("$raw?token=temporary")))
        assertNull(loader.intercept(request(headers = mapOf("Authorization" to "secret"))))
        assertNull(loader.intercept(request("https://example.com/a.png")))
        CookieManager.getInstance().setCookie(raw, "session=secret")
        assertNull(loader.intercept(request()))
        CookieManager.getInstance().removeAllCookies(null)
        assertNull(captured)
        loader.close()
    }

    @Test fun documentReplacementClosesOpenStreamsAndDisposedRendererDoesNotDispatch() = runTest {
        enabled()
        val loader = loader()
        loader.intercept(request())!!
        loader.cancelRequests()
        assertTrue(closed)
        closed = false
        loader.intercept(request())!!
        loader.close()
        assertTrue(closed)
        captured = null
        assertEquals(503, loader.intercept(request())!!.statusCode)
        assertNull(captured)
    }
}
