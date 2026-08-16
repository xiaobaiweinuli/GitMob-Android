package com.gitmob.app.core.network

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.GraphQLException
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.NetworkException
import com.gitmob.app.core.error.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URI
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@PublishedApi
internal const val REST_API_VERSION = "2026-03-10" // 最新版本（2026-03-10 发布），升级前先读 GitHub 变更日志
// graphQL()（inline）直接用到这个值，同 DEFAULT_ACCEPT 的道理
@PublishedApi
internal const val USER_AGENT = "GitMob-Android"     // REST 强制要求，缺失会被拒绝

// public inline 函数（get/post/patch/...）的默认参数用到了这个值，inline 函数的字节码
// 会被复制到调用方所在的位置，编译器要求它引用到的一切符号至少和它自己一样公开可见，
// private 不够格。@PublishedApi 是 Kotlin 官方为这种场景设计的注解：语义上仍然当作
// "不要在这个类外面直接用它"，但放行给 inline 函数用，不用把可见性放宽成完全 public。
@PublishedApi
internal const val DEFAULT_ACCEPT = "application/vnd.github+json"

/**
 * REST 和 GraphQL 共用同一个 OkHttpClient，但两套请求头完全不同、作用也不同，
 * 内部拆成两组独立方法各自拼头，不共用统一拦截器。详见技能 references/http-headers.md。
 *
 * 不使用 Retrofit——REST 端点数量不多，手写比维护 Retrofit interface 更直观。
 */
@Singleton
class GHApiClient @Inject constructor(
    // 下面这四个原来是 private val——同样是被 public inline 函数（graphQL 等）直接
    // 引用到，必须至少是 internal 才能通过编译，用 @PublishedApi 标记"仅供 inline 用，
    // 别当成正常的公开 API 调用"，道理同 DEFAULT_ACCEPT。
    @PublishedApi internal val okHttpClient: OkHttpClient,
    @PublishedApi internal val json: Json,
    @PublishedApi internal val tokenProvider: AccessTokenProvider,
    @Named("restBaseUrl") private val restBaseUrl: String,
    @Named("graphQLUrl") @PublishedApi internal val graphQLUrl: String,
) {
    // 生产环境的默认值由 NetworkModule 用 @Named 提供；单元测试里不走 Hilt，
    // 直接 new GHApiClient(..., restBaseUrl = mockServer.url("/").toString(), ...) 即可，
    // 见 references/testing.md。
    // ============ REST 系列 ============

    suspend inline fun <reified T> get(
        path: String,
        accept: String = DEFAULT_ACCEPT,
    ): T = executeRest("GET", path, body = null, accept = accept)

    suspend inline fun <reified T, reified B> post(
        path: String,
        body: B,
        accept: String = DEFAULT_ACCEPT,
    ): T = executeRest("POST", path, json.encodeToString(body), accept = accept)

    suspend inline fun <reified T, reified B> patch(
        path: String,
        body: B,
        accept: String = DEFAULT_ACCEPT,
    ): T = executeRest("PATCH", path, json.encodeToString(body), accept = accept)

    /** 不需要请求体的 PATCH（比如"标记单条通知已读"这种纯动作型接口） */
    suspend inline fun <reified T> patchNoBody(path: String, accept: String = DEFAULT_ACCEPT): T =
        executeRest("PATCH", path, body = null, accept = accept)

    suspend inline fun <reified T, reified B> put(
        path: String,
        body: B,
        accept: String = DEFAULT_ACCEPT,
    ): T = executeRest("PUT", path, json.encodeToString(body), accept = accept)

    /** 不需要请求体的 PUT（比如"全部标记已读"这种纯动作型接口） */
    suspend inline fun <reified T> putNoBody(path: String, accept: String = DEFAULT_ACCEPT): T =
        executeRest("PUT", path, body = null, accept = accept)

    suspend inline fun <reified T> delete(path: String, accept: String = DEFAULT_ACCEPT): T =
        executeRest("DELETE", path, body = null, accept = accept)

    // 拿原始内容（diff/patch/raw 文件等），不经过 JSON 解码
    suspend fun getRaw(path: String, accept: String): String = executeRestRaw("GET", path, accept)

    /**
     * Resolve a GitHub REST download endpoint without following its redirect.
     * The returned signed URL is deliberately unauthenticated so it can be opened
     * by an external browser without leaking the PAT.
     */
    suspend fun resolveRestDownloadUrl(path: String, accept: String = DEFAULT_ACCEPT): String? {
        val token = tokenProvider.getToken() ?: throw UnauthorizedException()
        val request = Request.Builder()
            .url("$restBaseUrl$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", REST_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val noRedirectClient = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        return withContext(Dispatchers.IO) {
            noRedirectClient.newCall(request).execute().use { response ->
                if (response.code !in setOf(301, 302, 303, 307, 308)) {
                    if (!response.isSuccessful) {
                        throw HttpStatusException(response.code, "Download endpoint returned ${response.code}")
                    }
                    return@use null
                }
                val location = response.header("Location")
                    ?: return@use null
                val resolved = runCatching { URI(request.url.toString()).resolve(location).normalize() }.getOrNull()
                    ?: return@use null
                if (!resolved.isAbsolute || !resolved.scheme.equals("https", ignoreCase = true) || resolved.userInfo != null) {
                    return@use null
                }
                resolved.toString()
            }
        }
    }

    /** Release asset 使用 uploads.github.com 的绝对 URL，仍复用统一鉴权和错误映射。 */
    suspend inline fun <reified T> uploadBytes(url: String, contentType: String, bytes: ByteArray): T {
        val token = tokenProvider.getToken() ?: throw UnauthorizedException()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", DEFAULT_ACCEPT)
            .header("X-GitHub-Api-Version", REST_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBytes = response.body.bytes()
                if (!response.isSuccessful) throw HttpStatusException(response.code, responseBytes.decodeToString())
                json.decodeFromString(responseBytes.decodeToString())
            }
        }
    }

    suspend inline fun <reified T> executeRest(method: String, path: String, body: String?, accept: String): T {
        rawCall(method, path, body, accept).use { response ->
            val bytes = response.body.bytes()
            if (!response.isSuccessful) throw HttpStatusException(response.code, bytes.decodeToString())
            // 部分 REST 接口成功时返回 204 No Content（空响应体），比如通知相关的
            // PATCH/PUT/DELETE——这种情况调用方声明的 T 应该是 Unit，直接返回，
            // 不要尝试对空字符串做 JSON 解码（会抛序列化异常）。
            if (bytes.isEmpty() && T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                return Unit as T
            }
            return json.decodeFromString(bytes.decodeToString())
        }
    }

    suspend fun executeRestRaw(method: String, path: String, accept: String): String {
        rawCall(method, path, body = null, accept = accept).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw HttpStatusException(response.code, text)
            return text
        }
    }

    // executeRest（public inline）直接调用这个函数，同样必须至少 internal 才能过编译，
    // 但它自己内部访问 restBaseUrl 这些私有成员没问题——它本身不是 inline，字节码不会被
    // 复制到别处，"泄漏"规则只约束 inline 函数体直接引用到的符号，不会顺着调用链传递。
    @PublishedApi
    internal suspend fun rawCall(method: String, path: String, body: String?, accept: String): Response {
        val token = tokenProvider.getToken() ?: throw UnauthorizedException()
        val requestBuilder = Request.Builder()
            .url("$restBaseUrl$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)                          // REST 专属：媒体类型协商，按调用方传入
            .header("X-GitHub-Api-Version", REST_API_VERSION)   // REST 专属：版本锁定，GraphQL 没有这个概念
            .header("User-Agent", USER_AGENT)

        val request = when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(body!!.toRequestBody("application/json".toMediaType()))
            "PATCH" -> if (body != null) {
                requestBuilder.patch(body.toRequestBody("application/json".toMediaType()))
            } else {
                requestBuilder.patch("".toRequestBody(null))
            }
            "PUT" -> if (body != null) {
                requestBuilder.put(body.toRequestBody("application/json".toMediaType()))
            } else {
                // 有些 REST 接口的 PUT 不需要请求体（比如"全部标记已读"），
                // OkHttp 的 put() 必须传 RequestBody，用空 body 占位
                requestBuilder.put("".toRequestBody(null))
            }
            "DELETE" -> requestBuilder.delete()
            else -> throw IllegalArgumentException("不支持的方法: $method")
        }.build()

        // ⚠️ OkHttp Call.execute() 是同步阻塞 API（DNS 解析 + TCP 握手 + SSL 握手 + socket 读写全程阻塞）
        // 必须切到 Dispatchers.IO 再执行，否则在 Main 线程调用会被 Android StrictMode 拦截成
        // NetworkOnMainThreadException，safeCall 归类成 catch-all 后 UI 就会看到"未知错误"。
        // 参考 Kotlin 官方协程文档：suspend 函数必须保持"main-safe"，不得阻塞调用方线程。
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
            // 仓库 zipball/tarball 下载走 302 跳转到 codeload.github.com，
            // OkHttpClient 默认 followRedirects(true) 会自动处理。
        }
    }

    // ============ GraphQL 单一入口 ============

    /**
     * GitHub GraphQL 单一入口（登录校验请求走这里）。
     * token 校验失败会通过异常层级向上抛出；调用方用 safeCall 统一归类。
     * @sample userRepository.getViewerProfile（登录时的首次验证请求）
     */
    suspend inline fun <reified D> graphQL(
        query: String,
        variables: Map<String, JsonElement> = emptyMap(),
    ): D {
        // Step 1: 拿 token（会走 TokenStorage -> TokenCipher 解密）
        val token = tokenProvider.getToken() ?: throw UnauthorizedException()

        // Step 2: 构造请求
        val bodyStr = json.encodeToString(GraphQLRequest(query, variables))
        val request = Request.Builder()
            .url(graphQLUrl)
            .header("Authorization", "bearer $token")           // 大小写均可，GraphQL 官方示例用小写
            .header("Content-Type", "application/json")         // GraphQL 必需：所有操作都是 POST + JSON body
            .header("User-Agent", USER_AGENT)
            // 注意：不加 Accept 媒体类型头、不加 X-GitHub-Api-Version —— GraphQL 没有这两个概念
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()

        // Step 3: 发起 HTTP 请求 —— OkHttp execute() 是同步阻塞 API，必须切 Dispatchers.IO
        //         否则 viewModelScope.launch 默认 Main 线程会触发 NetworkOnMainThreadException
        //         （直接被 safeCall catch-all 归类为"未知错误"）。
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                val bytes = response.body.bytes()

                // 非成功 HTTP 状态码
                if (!response.isSuccessful) {
                    throw HttpStatusException(response.code, bytes.decodeToString())
                }

                // Step 4: JSON 解码
                val bodyString = bytes.decodeToString()
                val parsed = try {
                    json.decodeFromString<GraphQLResponse<D>>(bodyString)
                } catch (e: kotlinx.serialization.SerializationException) {
                    throw e
                } catch (t: Throwable) {
                    throw t
                }

                // GraphQL 业务层错误（HTTP 200 也可能带 errors）
                if (!parsed.errors.isNullOrEmpty()) {
                    throw GraphQLException(parsed.errors)
                }

                // data 为空但也没 errors —— 罕见
                parsed.data ?: throw NetworkException("data 字段为空")
            }
        }
    }
}
