package com.gitmob.android.api

import android.content.Context
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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
    val okHttpClient: OkHttpClient get() = _okHttpClient ?: error("ApiClient not initialized")

    /** 全局 401/Token 失效事件——任何地方收到 401 都会 emit true */
    private val _tokenExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tokenExpired: SharedFlow<Unit> = _tokenExpired

    fun init(storage: TokenStorage) {
        tokenStorage = storage
        rebuild()
    }

    /**
     * 重试拦截器（网络层）
     *
     * 处理策略：
     * - SocketTimeoutException / SSLException / 连接重置/关闭 / SETTINGS preface：最多重试3次，指数退避
     * - "stream was reset: CANCEL"：HTTP/2 连接问题，立即重试到新连接（不等待）
     * - "Canceled"：OkHttp 内部取消（Call.cancel），不重试
     * - 5xx 服务器错误：最多重试2次，指数退避
     * - 其他 IOException：不重试，直接抛出
     *
     * 注意：此拦截器必须放在 authInterceptor 外层（addInterceptor 最后），
     *       使重试时也能经过 auth 拦截器重新附加 token。
     */
    private class RetryInterceptor : Interceptor {
        companion object {
            private const val MAX_NET_RETRIES = 3
            private const val MAX_SERVER_RETRIES = 2
            private const val INITIAL_DELAY_MS = 1000L
            private const val BACKOFF_FACTOR = 2.0
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            var lastException: IOException? = null

            // ── 网络层重试 ────────────────────────────────────────────────
            for (attempt in 0 until MAX_NET_RETRIES) {
                try {
                    // 每次重试都重新调用 chain.proceed()，这样会重新经过内层的 authInterceptor
                    val response = chain.proceed(chain.request())

                    // 5xx 服务器错误重试
                    if (response.code >= 500 && attempt < MAX_SERVER_RETRIES) {
                        response.close()
                        val delay = calculateDelay(attempt)
                        LogManager.w(TAG, "服务器错误 ${response.code}，重试 ${attempt + 1}/$MAX_SERVER_RETRIES，等待 ${delay}ms")
                        Thread.sleep(delay)
                        continue  // 继续循环，进行下一次重试
                    }
                    return response
                } catch (e: IOException) {
                    // "Canceled" = OkHttp Call 被主动取消（协程取消），不应重试
                    if (e.message == "Canceled") throw e

                    // "stream was reset: CANCEL" = HTTP/2 连接问题，立即重试（不等待）
                    val isStreamResetCancel = e.message?.contains("stream was reset: CANCEL") == true

                    val retryable = e is SocketTimeoutException ||
                        e is SSLException ||
                        e.message?.contains("Connection reset") == true ||
                        e.message?.contains("Connection closed") == true ||
                        e.message?.contains("SETTINGS preface") == true ||
                        isStreamResetCancel

                    if (retryable && attempt < MAX_NET_RETRIES - 1) {
                        lastException = e
                        val delay = if (isStreamResetCancel) 0L else calculateDelay(attempt)
                        LogManager.w(TAG, "网络异常 [${e.javaClass.simpleName}] ${e.message}，重试 ${attempt + 1}/$MAX_NET_RETRIES${if (delay > 0) "，等待 ${delay}ms" else ""}")
                        if (delay > 0) {
                            Thread.sleep(delay)
                        }
                    } else {
                        throw e
                    }
                }
            }
            throw lastException ?: IOException("请求失败，已超出最大重试次数")
        }

        /**
         * 计算指数退避延迟时间（含抖动）
         */
        private fun calculateDelay(attempt: Int): Long {
            val baseDelay = (INITIAL_DELAY_MS * Math.pow(BACKOFF_FACTOR, attempt.toDouble())).toLong()
            // 添加抖动：在 0.75x - 1.25x 之间随机
            val jitter = 0.75 + Math.random() * 0.5
            return (baseDelay * jitter).toLong()
        }
    }

    fun rebuild() {
        val logging = HttpLoggingInterceptor { msg -> LogManager.v(TAG, msg) }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val authInterceptor = Interceptor { chain ->
            val token = runBlocking { tokenStorage.accessToken.first() }
            val requestBuilder = chain.request().newBuilder()
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
            
            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
            
            val request = requestBuilder.build()
            val response = chain.proceed(request)
            
            if (response.code == 401 && !token.isNullOrBlank()) {
                LogManager.w(TAG, "收到 401，token 已失效，清除本地授权并触发重新登录")
                runBlocking { tokenStorage.clear() }
                _tokenExpired.tryEmit(Unit)
            }
            
            response
        }

        val okHttpClientBuilder = OkHttpClient.Builder()
            // 顺序重要：RetryInterceptor 在最外层（第一个添加），每次重试都会经过内层的 authInterceptor
            .addInterceptor(RetryInterceptor())
            .addInterceptor(logging)
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(
                maxIdleConnections = 16,
                keepAliveDuration = 3,
                timeUnit = TimeUnit.MINUTES
            ))

        _okHttpClient = okHttpClientBuilder.build()

        _api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(_okHttpClient!!)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubApi::class.java)
    }

    fun currentToken(): String? = runBlocking { tokenStorage.accessToken.first() }

    /**
     * 创建干净的 OkHttpClient（不继承任何拦截器）
     *
     * 用于 Token 验证等场景，避免与认证拦截器冲突
     */
    fun rawHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
}