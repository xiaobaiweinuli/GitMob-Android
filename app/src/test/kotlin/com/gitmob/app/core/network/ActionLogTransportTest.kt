package com.gitmob.app.core.network

import com.gitmob.app.testutil.TestAccessTokenProvider
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ActionLogTransportTest {
    @get:Rule val accounts = AccountSessionRule()
    private fun api(body: String) = GHApiClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            assertNull(chain.request().header("X-GitHub-Api-Version"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build(), Json, object : TestAccessTokenProvider { override suspend fun getToken(): String = error("Signed logs must not ask for a token") }, "https://api.example",
    )

    @Test fun `byte limit truncates without corrupting a multibyte code point`() = runTest(accounts.context) {
        val result = api("ab中文tail").getActionLogPreview("https://logs.example/log?secret=temporary", maxBytes = 6)
        assertEquals("ab中", result.text)
        assertTrue(result.truncated)
    }
    @Test fun `line limit keeps original CRLF and stops before later lines`() = runTest(accounts.context) {
        val result = api("one\r\ntwo\r\nthree").getActionLogPreview("https://logs.example/log", maxLines = 2)
        assertEquals("one\r\ntwo\r\n", result.text)
        assertTrue(result.truncated)
    }
    @Test fun `empty and exact byte or line limits are not reported as truncated`() = runTest(accounts.context) {
        for ((text, bytes, lines) in listOf(Triple("", 10, 10), Triple("abcd", 4, 10), Triple("a\nb\n", 99, 2))) {
            val result = api(text).getActionLogPreview("https://logs.example/log", bytes, lines)
            assertEquals(text, result.text)
            assertFalse(result.truncated)
        }
    }
    @Test fun `megabyte single line stays within preview bound`() = runTest(accounts.context) {
        val result = api("x".repeat(2 * 1024 * 1024)).getActionLogPreview("https://logs.example/log")
        assertEquals(1024 * 1024, result.text.length)
        assertTrue(result.truncated)
    }
    @Test fun `network errors do not expose the signed URL in their message or cause`() = runTest(accounts.context) {
        val client = GHApiClient(OkHttpClient.Builder().addInterceptor { throw IOException("https://logs.example/?signature=secret") }.build(), Json,
            object : TestAccessTokenProvider { override suspend fun getToken() = "fake" }, "https://api.example")
        try {
            client.getActionLogPreview("https://logs.example/log")
            fail("Expected IOException")
        } catch (error: IOException) {
            assertFalse(error.message.orEmpty().contains("signature"))
            assertNull(error.cause)
        }
    }
    @Test fun `account switch while waiting for a download token prevents HTTP`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = GHApiClient(OkHttpClient.Builder().addInterceptor { error("Must not make a request") }.build(), Json,
            object : TestAccessTokenProvider { override suspend fun getToken(): String { waiting.complete(Unit); release.await(); return "old-token" } }, "https://api.example")
        val work = async(accounts.context) { client.resolveRestDownloadUrl("/logs") }
        waiting.await()
        accounts.session.replace(emptyList(), null)
        release.complete(Unit)
        try { work.await(); fail("Expected account cancellation") } catch (_: CancellationException) { }
    }
}
