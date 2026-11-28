package com.gitmob.app.core.network

import com.gitmob.app.core.preferences.RawAccelerationPreferenceStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RawAccelerationInterceptor @Inject constructor(
    private val preferences: RawAccelerationPreferenceStore,
    private val policy: RawUrlPolicy,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = request.url.toString()
        val headers = request.headers.toMap()
        if (!policy.accepts(original, request.method, headers)) return chain.proceed(request)
        val settings = try { runBlocking { preferences.initialize() } }
            catch (error: Exception) { throw IOException("Cannot read network preferences", error) }
        val target = policy.rewrite(original, settings.activePrefix, request.method, headers)
        if (target == original) return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(target)
            .removeHeader("Host").removeHeader("X-GitHub-Api-Version").build())
    }
}
