package com.gitmob.app.ui.common

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.runtime.staticCompositionLocalOf
import com.gitmob.app.core.network.RawUrlPolicy
import com.gitmob.app.core.preferences.RawAccelerationPreferenceStore
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal val LocalRawWebResourceLoader = staticCompositionLocalOf<RawWebResourceLoader.Factory?> { null }

/** A renderer owns the lifetime of calls and streams handed to WebView. */
class RawWebResourceLoader private constructor(
    private val client: OkHttpClient,
    private val preferences: RawAccelerationPreferenceStore,
    private val policy: RawUrlPolicy,
) : AutoCloseable {
    private val calls = ConcurrentHashMap<Call, Response>()
    private val lock = Any()
    private var closed = false
    private var generation = 0L

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!policy.accepts(url, request.method, request.requestHeaders) ||
            !CookieManager.getInstance().getCookie(url).isNullOrEmpty()) return null
        val requestGeneration = synchronized(lock) {
            if (closed) return unavailable()
            generation
        }
        try {
            if (runBlocking { preferences.initialize() }.activePrefix == null) return null
            val builder = Request.Builder().url(url).method(request.method, null)
            request.requestHeaders.filterKeys { it.lowercase() in FORWARDED_HEADERS }
                .forEach { (key, value) -> builder.header(key, value) }
            val call = client.newCall(builder.build())
            synchronized(lock) {
                if (closed || generation != requestGeneration) return unavailable()
                pending.add(call)
            }
            val response = try { call.execute() } catch (error: IOException) {
                synchronized(lock) { pending.remove(call) }
                throw error
            }
            synchronized(lock) {
                if (closed || !pending.remove(call)) { response.close(); return unavailable() }
                calls[call] = response
            }
            val type = response.body.contentType()
            if (response.code !in 100..599 || response.code in 300..399) {
                release(call, response)
                return unavailable()
            }
            val stream = object : FilterInputStream(response.body.byteStream()) {
                override fun close() { release(call, response) }
            }
            return WebResourceResponse(type?.let { "${it.type}/${it.subtype}" }, type?.charset()?.name(),
                response.code, "Response",
                response.headers.toMap().filterKeys { !it.equals("Set-Cookie", true) }, stream)
        } catch (_: IOException) { return unavailable() }
    }

    private val pending = mutableSetOf<Call>()

    private fun release(call: Call, response: Response) {
        calls.remove(call)
        response.close()
    }

    fun cancelRequests() = synchronized(lock) {
        generation++
        pending.forEach { it.cancel() }
        pending.clear()
        calls.forEach { (call, response) -> call.cancel(); response.close() }
        calls.clear()
    }

    override fun close() = synchronized(lock) { closed = true; cancelRequests() }

    private fun unavailable() = WebResourceResponse("text/plain", "UTF-8", 503, "Unavailable",
        mapOf("Cache-Control" to "no-store"), byteArrayOf().inputStream())

    @Singleton
    class Factory @Inject constructor(
        private val client: OkHttpClient,
        private val preferences: RawAccelerationPreferenceStore,
        private val policy: RawUrlPolicy,
    ) {
        fun create() = RawWebResourceLoader(client, preferences, policy)
    }

    private companion object {
        val FORWARDED_HEADERS = setOf("accept", "accept-language", "range", "if-range")
    }
}
