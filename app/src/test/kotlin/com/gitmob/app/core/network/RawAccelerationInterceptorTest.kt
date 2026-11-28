package com.gitmob.app.core.network

import com.gitmob.app.core.preferences.*
import com.gitmob.app.testutil.NetworkPreferencesFixture
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RawAccelerationInterceptorTest {
    @Test fun readsLatestSettingAtDispatchAndLeavesAuthenticatedOrApiRequestsIntact() = runTest {
        val store = RawAccelerationPreferenceStore(NetworkPreferencesFixture())
        val raw = "https://raw.githubusercontent.com/o/r/main/image.png"
        val captured = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(RawAccelerationInterceptor(store, RawUrlPolicy()))
            .addInterceptor { chain ->
                captured.add(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("ok".toResponseBody()).build()
            }.build()
        fun send(url: String, auth: Boolean = false) {
            val request = Request.Builder().url(url).header("X-GitHub-Api-Version", "2022-11-28")
            if (auth) request.header("Authorization", "Bearer test-token")
            client.newCall(request.build()).execute().close()
        }
        send(raw)
        assertEquals(raw, captured.last().url.toString())
        store.update { it.copy(services = listOf(RawAccelerationService("a", "One", "https://proxy.example/path/")), selectedId = "a", enabled = true) }
        send(raw)
        assertEquals("https://proxy.example/path/$raw", captured.last().url.toString())
        assertNull(captured.last().header("Authorization"))
        assertNull(captured.last().header("X-GitHub-Api-Version"))
        send(raw, true)
        assertEquals(raw, captured.last().url.toString())
        assertEquals("Bearer test-token", captured.last().header("Authorization"))
        send("$raw?token=temporary")
        assertEquals("$raw?token=temporary", captured.last().url.toString())
        val api = "https://api.github.com/repos/o/r/actions/runs/1/logs"
        send(api, true)
        assertEquals(api, captured.last().url.toString())
        assertEquals("2022-11-28", captured.last().header("X-GitHub-Api-Version"))
        store.update { it.copy(enabled = false) }
        send(raw)
        assertEquals(raw, captured.last().url.toString())
    }
}
