package com.gitmob.app.core.network

import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.TestAccessTokenProvider
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*

class BackgroundJsonTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var client: GHApiClient
    @Before fun setup() {
        TrackingSerializer.threads.clear()
        server = MockWebServer().apply { start() }
        client = GHApiClient(OkHttpClient(), Json { ignoreUnknownKeys = true; encodeDefaults = true },
            object : TestAccessTokenProvider { override suspend fun getToken() = "token" }, server.url("/").toString().trimEnd('/'))
    }
    @After fun cleanup() = server.close()

    @Test fun restRequestAndResponseSerializationRunsOffCallerForEveryVerb() = runTest(accounts.context) {
        val caller = Thread.currentThread()
        repeat(4) { server.enqueue(MockResponse(body = "\"response\"")) }
        assertEquals(Tracked("response"), client.get<Tracked>("/get"))
        assertEquals(Tracked("response"), client.post<Tracked, Tracked>("/post", Tracked("request")))
        assertEquals(Tracked("response"), client.patch<Tracked, Tracked>("/patch", Tracked("request")))
        assertEquals(Tracked("response"), client.put<Tracked, Tracked>("/put", Tracked("request")))
        assertEquals(7, TrackingSerializer.threads.size)
        assertTrue(TrackingSerializer.threads.all { it !== caller })
        assertEquals("GET", server.takeRequest().method)
        for (method in listOf("POST", "PATCH", "PUT")) {
            val request = server.takeRequest()
            assertEquals(method, request.method)
            assertEquals("\"request\"", requireNotNull(request.body).utf8())
        }
    }

    @Test fun publicJsonIsDecodedOffCallerWithoutAuthentication() = runTest {
        val caller = Thread.currentThread()
        server.enqueue(MockResponse(body = "\"public\""))
        assertEquals(Tracked("public"), client.getPublic<Tracked>("/public"))
        assertTrue(TrackingSerializer.threads.single() !== caller)
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test fun jsonConfigurationEmptyResponsesAndRawTextKeepTheirContracts() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = """{"name":"response","unknown":true}"""))
        assertEquals(Configured("response"), client.post<Configured, Configured>("/config", Configured("request")))
        assertEquals("""{"name":"request","count":7}""", requireNotNull(server.takeRequest().body).utf8())
        server.enqueue(MockResponse(code = 204))
        assertEquals(Unit, client.delete<Unit>("/empty"))
        server.enqueue(MockResponse(body = "中文\nraw"))
        assertEquals("中文\nraw", client.getRaw("/raw", "text/plain"))
    }

    @Test fun serializationErrorsStillPropagateBeforeSendingARequest() = runTest(accounts.context) {
        val failure = runCatching { client.post<Tracked, Tracked>("/bad", Tracked("fail")) }.exceptionOrNull()
        assertTrue(failure is SerializationException)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse(body = "{invalid"))
        assertTrue(runCatching { client.get<Configured>("/invalid") }.exceptionOrNull() is SerializationException)
    }

    @Serializable data class Configured(val name: String, val count: Int = 7)
    @Serializable(with = TrackingSerializer::class) data class Tracked(val value: String)
    object TrackingSerializer : KSerializer<Tracked> {
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        override val descriptor = PrimitiveSerialDescriptor("Tracked", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: Tracked) {
            threads += Thread.currentThread()
            if (value.value == "fail") throw SerializationException("test")
            encoder.encodeString(value.value)
        }
        override fun deserialize(decoder: Decoder): Tracked {
            threads += Thread.currentThread()
            return Tracked(decoder.decodeString())
        }
    }
}
