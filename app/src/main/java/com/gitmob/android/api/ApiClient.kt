package com.gitmob.android.api

import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

object ApiClient {

    private const val BASE_URL = "https://api.github.com/"
    private const val TAG = "ApiClient"
    private lateinit var tokenStorage: TokenStorage
    private var _api: GitHubApi? = null
    private var _okHttpClient: OkHttpClient? = null

    val api: GitHubApi get() = _api ?: error("ApiClient not initialized")

    /** 全局 401/Token 失效事件——任何地方收到 401 都会 emit true */
    private val _tokenExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tokenExpired: SharedFlow<Unit> = _tokenExpired

    fun init(storage: TokenStorage) {
        tokenStorage = storage
        rebuild()
    }

    fun clearConnectionPool() {
        _okHttpClient?.connectionPool?.evictAll()
        LogManager.i(TAG, "连接池已清理")
    }

    /**
     * 重试拦截器（网络层）
     *
     * 处理策略：
     * - SocketTimeoutException / SSLException / 连接重置：最多重试3次，指数退避
     * - "Canceled"：OkHttp 内部取消（连接池回收/Call.cancel），不重试（避免无意义重试）
     * - 5xx 服务器错误：最多重试2次
     * - 其他 IOException：不重试，直接抛出
     *
     * 注意：此拦截器必须放在 authInterceptor 外层（addInterceptor 最后），
     *       使重试时也能经过 auth 拦截器重新附加 token。
     */
    private class RetryInterceptor : Interceptor {
        companion object {
            private const val MAX_NET_RETRIES = 3
            private const val MAX_SERVER_RETRIES = 2
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var lastException: IOException? = null

            // ── 网络层重试 ────────────────────────────────────────────────
            repeat(MAX_NET_RETRIES) { attempt ->
                try {
                    val response = chain.proceed(request)

                    // 5xx 服务器错误重试
                    if (response.code >= 500 && attempt < MAX_SERVER_RETRIES) {
                        response.close()
                        LogManager.w(TAG, "服务器错误 ${response.code}，重试 ${attempt + 1}/$MAX_SERVER_RETRIES")
                        Thread.sleep(800L * (attempt + 1))
                        return@repeat
                    }
                    return response
                } catch (e: IOException) {
                    // "Canceled" = OkHttp Call 被主动取消（协程取消 / 连接池回收），
                    // 不应重试——协程已取消时重试毫无意义且可能造成资源泄漏
                    if (e.message == "Canceled") throw e

                    val retryable = e is SocketTimeoutException ||
                        e is SSLException ||
                        e.message?.contains("Connection reset") == true ||
                        e.message?.contains("Connection closed") == true ||
                        e.message?.contains("SETTINGS preface") == true ||
                        e.message?.contains("stream was reset") == true

                    if (retryable && attempt < MAX_NET_RETRIES - 1) {
                        lastException = e
                        LogManager.w(TAG, "网络异常 [${e.javaClass.simpleName}] ${e.message}，重试 ${attempt + 1}/$MAX_NET_RETRIES")
                        Thread.sleep(600L * (attempt + 1))
                    } else {
                        throw e
                    }
                }
            }
            throw lastException ?: IOException("请求失败，已超出最大重试次数")
        }
    }

    fun rebuild() {
        val logging = HttpLoggingInterceptor { msg -> LogManager.v(TAG, msg) }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val authInterceptor = Interceptor { chain ->
            val token = runBlocking { tokenStorage.accessToken.first() }
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val response = chain.proceed(request)
            if (response.code == 401) {
                LogManager.w(TAG, "收到 401，token 已失效，清除本地授权并触发重新登录")
                runBlocking { tokenStorage.clear() }
                _tokenExpired.tryEmit(Unit)
            }
            response
        }

        _okHttpClient = OkHttpClient.Builder()
            // 顺序重要：RetryInterceptor 在最外层，每次重试都会经过内层的 authInterceptor
            .addInterceptor(RetryInterceptor())
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // 增大读超时，减少因慢速响应触发 Canceled
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)   // 整体请求超时兜底
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(5, 3, TimeUnit.MINUTES))  // 缩小连接池，减少空闲连接被回收触发 Canceled
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()

        _api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(_okHttpClient!!)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubApi::class.java)
    }

    fun currentToken(): String? = runBlocking { tokenStorage.accessToken.first() }

    fun rawHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
}