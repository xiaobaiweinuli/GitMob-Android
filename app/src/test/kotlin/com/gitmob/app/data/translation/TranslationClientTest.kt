package com.gitmob.app.data.translation

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TranslationClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = TranslationClient(json)
    private val custom = TranslationPreferences(provider = TranslationProvider.CUSTOM)

    @Test fun customJsonEscapesUserTextWithoutReplacingItsPlaceholders() {
        val input = "Quotes: \"hello\"\n{{target}} and \\ slash"
        val request = client.buildRequest(input, custom, TranslationCredentials(endpoint = "https://example.test/translate", auth = TranslationAuth.BEARER, secret = "only-custom"))
        val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(input, body["q"]!!.jsonPrimitive.content)
        assertEquals("zh-CN", body["target"]!!.jsonPrimitive.content)
        assertEquals("Bearer only-custom", request.header("Authorization"))
        val anonymous = client.buildRequest("hello", custom.copy(provider = TranslationProvider.EDGE), TranslationCredentials(secret = "only-custom"))
        assertNull(anonymous.header("Authorization"))
        assertNull(anonymous.header("Cookie"))
        assertNull(anonymous.header("X-GitHub-Api-Version"))
        assertFalse(Buffer().also { anonymous.body!!.writeTo(it) }.readUtf8().contains("only-custom"))
    }

    @Test fun customGetEncodesQueryAndKeepsConfiguredHeaderSeparate() {
        val text = "a&b=1\n你好"
        val request = client.buildRequest(text, custom, TranslationCredentials(endpoint = "https://example.test/t?mode=translate", method = "GET", auth = TranslationAuth.HEADER, header = "X-API-Key", secret = "test-key"))
        assertEquals("GET", request.method)
        assertEquals(text, request.url.queryParameter("q"))
        assertEquals("translate", request.url.queryParameter("mode"))
        assertEquals("test-key", request.header("X-API-Key"))
        assertFalse(request.url.toString().contains("test-key"))
    }

    @Test fun customRejectsUnsafeOrMalformedConfiguration() {
        listOf(
            TranslationCredentials(endpoint = "http://example.test/t"),
            TranslationCredentials(endpoint = "https://user:password@example.test/t"),
            TranslationCredentials(endpoint = "https://example.test/t#fragment"),
            TranslationCredentials(endpoint = "https://example.test/t", template = "not json"),
            TranslationCredentials(endpoint = "https://example.test/t", template = "{}"),
            TranslationCredentials(endpoint = "https://example.test/t", method = "DELETE"),
            TranslationCredentials(endpoint = "https://example.test/t", auth = TranslationAuth.HEADER, header = "Host", secret = "elsewhere.test"),
            TranslationCredentials(endpoint = "https://example.test/t", auth = TranslationAuth.BEARER),
        ).forEach { credentials ->
            assertEquals(TranslationProblem.CONFIGURATION, assertThrows(TranslationException::class.java) { client.buildRequest("hello", custom, credentials) }.problem)
        }
    }

    @Test fun baiduSignatureMatchesKnownVector() {
        val request = client.buildRequest("apple", TranslationPreferences(provider = TranslationProvider.BAIDU), TranslationCredentials(appId = "2015063000000001", secret = "12345678"), salt = "1435660288")
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("f89f9594663708c1605f3d736d01d2d4", fields["sign"])
        assertEquals("zh", fields["to"])
        assertFalse(fields.values.contains("12345678"))
    }

    @Test fun youdaoSignsUnicodeUsingCodePoints() {
        val input = "😀".repeat(21)
        assertEquals("😀".repeat(10) + "21" + "😀".repeat(10), TranslationClient.youdaoInput(input))
        assertEquals("😀".repeat(20), TranslationClient.youdaoInput("😀".repeat(20)))
        val request = client.buildRequest("hello", TranslationPreferences(provider = TranslationProvider.YOUDAO_OPEN), TranslationCredentials(appId = "app", secret = "key"), salt = "salt", time = "1700000000")
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("v3", fields["signType"])
        assertEquals(TranslationClient.digest("SHA-256", "apphellosalt1700000000key"), fields["sign"])
    }

    @Test fun arrayServicesSendAllUnitsAndRejectMissingOrExtraResults() {
        val texts = listOf("Hello", "One\nTwo", "Quotes: \"three\"")
        listOf(TranslationProvider.EDGE, TranslationProvider.TRANSMART).forEach { provider ->
            val request = client.buildBatchRequest(texts, TranslationPreferences(provider = provider), TranslationCredentials())
            val data = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val actual = if (provider == TranslationProvider.EDGE) data.jsonArray else data.jsonObject["source"]!!.jsonObject["text_list"]!!.jsonArray
            assertEquals(texts, actual.map { it.jsonPrimitive.content })
            val response = if (provider == TranslationProvider.EDGE) "[\"你好\",\"一\\n二\",\"三\"]" else "{\"auto_translation\":[\"你好\",\"一\\n二\",\"三\"]}"
            assertEquals(listOf("你好", "一\n二", "三"), client.parseBatchResponse(provider, json.parseToJsonElement(response), "", 3))
            assertEquals(TranslationProblem.MAPPING, assertThrows(TranslationException::class.java) { client.parseBatchResponse(provider, json.parseToJsonElement(response), "", 2) }.problem)
        }
    }

    @Test fun retryAfterAcceptsSecondsAndHttpDates() {
        assertEquals(3000L, TranslationClient.retryAfter("3", 0))
        assertEquals(3000L, TranslationClient.retryAfter("Thu, 01 Jan 1970 00:00:03 GMT", 0))
        assertNull(TranslationClient.retryAfter("invalid", 0))
    }

    @Test fun responseParsersHandleBusinessErrorsAndNestedCustomPaths() {
        fun parse(provider: TranslationProvider, value: String, path: String = "translatedText") = client.parseResponse(provider, json.parseToJsonElement(value), path)
        assertEquals("你好", parse(TranslationProvider.CUSTOM, """{"data":[{"text":"你好"}]}""", "data[0].text"))
        assertEquals("你好", parse(TranslationProvider.YOUDAO_OPEN, """{"errorCode":"0","translation":["你好"]}"""))
        assertEquals("你好", parse(TranslationProvider.EDGE, """["你好"]"""))
        assertEquals("你好", parse(TranslationProvider.EDGE, """[{"detectedLanguage":{"language":"en","score":0.9},"translations":[{"text":"你好","to":"zh-Hans"}]}]"""))
        assertEquals("你好", parse(TranslationProvider.TRANSMART, """{"auto_translation":["你好"]}"""))
        assertEquals(TranslationProblem.LIMIT, assertThrows(TranslationException::class.java) { parse(TranslationProvider.YOUDAO_OPEN, """{"errorCode":411}""") }.problem)
        assertEquals(TranslationProblem.QUOTA, assertThrows(TranslationException::class.java) { parse(TranslationProvider.BAIDU, """{"error_code":"54004"}""") }.problem)
        assertEquals(TranslationProblem.QUOTA, assertThrows(TranslationException::class.java) { parse(TranslationProvider.YOUDAO_OPEN, """{"errorCode":"401"}""") }.problem)
        assertEquals(TranslationProblem.RESPONSE, assertThrows(TranslationException::class.java) { parse(TranslationProvider.CUSTOM, "{}", "missing") }.problem)
    }

    @Test fun transportDoesNotFollowRedirectWithCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")))
            val error = try { client.execute(Request.Builder().url(server.url("/translate")).header("Authorization", "Bearer custom-key").build()); null } catch (e: TranslationException) { e }
            assertEquals(TranslationProblem.NETWORK, error?.problem)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun transportLimitsResponseSizeAndMapsRateLimit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            for (expected in listOf(TranslationProblem.LIMIT, TranslationProblem.TOO_LONG)) {
                val error = try { client.execute(Request.Builder().url(server.url("/translate")).build()); null } catch (e: TranslationException) { e }
                assertEquals(expected, error?.problem)
            }
        }
    }

    @Test fun transportCancellationDoesNotWaitForResponseTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = launch(Dispatchers.IO) { client.execute(Request.Builder().url(server.url("/translate")).build()) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        }
    }
}
