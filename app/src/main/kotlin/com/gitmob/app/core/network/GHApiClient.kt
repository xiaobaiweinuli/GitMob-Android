package com.gitmob.app.core.network

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.UnauthorizedException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@PublishedApi
internal const val REST_API_VERSION = "2026-03-10" // 最新版本（2026-03-10 发布），升级前先读 GitHub 变更日志
@PublishedApi
internal const val USER_AGENT = "GitMob-Android"     // REST 强制要求，缺失会被拒绝

// public inline 函数（get/post/patch/...）的默认参数用到了这个值，inline 函数的字节码
// 会被复制到调用方所在的位置，编译器要求它引用到的一切符号至少和它自己一样公开可见，
// private 不够格。@PublishedApi 是 Kotlin 官方为这种场景设计的注解：语义上仍然当作
// "不要在这个类外面直接用它"，但放行给 inline 函数用，不用把可见性放宽成完全 public。
@PublishedApi
internal const val DEFAULT_ACCEPT = "application/vnd.github+json"

/**
 * 纯 REST 客户端。GraphQL 已全部迁移到 Apollo Kotlin（见 NetworkModule.provideApolloClient），
 * 两套通道复用同一个 OkHttpClient 实例（超时/连接池一致），请求头各自拼接。
 * 详见技能 references/http-headers.md。
 *
 * 不使用 Retrofit——REST 端点数量不多，手写比维护 Retrofit interface 更直观。
 */
@Singleton
class GHApiClient @Inject constructor(
    // 下面三个原来是 private val——被 public inline 函数（get/post/patch 等）直接
    // 引用到，必须至少是 internal 才能通过编译，用 @PublishedApi 标记"仅供 inline 用，
    // 别当成正常的公开 API 调用"，道理同 DEFAULT_ACCEPT。
    @PublishedApi internal val okHttpClient: OkHttpClient,
    @PublishedApi internal val json: Json,
    @PublishedApi internal val tokenProvider: AccessTokenProvider,
    @Named("restBaseUrl") private val restBaseUrl: String,
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

    /** 二进制文件和目录 ZIP 组装使用；不得先转 String，避免破坏任意字节内容。 */
    suspend fun getRawBytes(path: String, accept: String): ByteArray =
        readRest("GET", path, body = null, accept = accept)

    /**
     * Resolve a GitHub REST download endpoint without following its redirect.
     * The returned signed URL is deliberately unauthenticated so it can be opened
     * by an external browser without leaking the PAT.
     */
    suspend fun resolveRestDownloadUrl(path: String, accept: String = DEFAULT_ACCEPT): String? {
        val epoch = AccountContext.ensureCurrent()
        val token = tokenProvider.getToken() ?: throw UnauthorizedException()
        epoch.ensureCurrent()
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
        return noRedirectClient.newCall(request).readCancellable { response ->
            if (response.code !in setOf(301, 302, 303, 307, 308)) {
                if (!response.isSuccessful) {
                    throw HttpStatusException(response.code, "Download endpoint returned ${response.code}")
                }
                return@readCancellable null
            }
            val location = response.header("Location") ?: return@readCancellable null
            val resolved = runCatching { URI(request.url.toString()).resolve(location).normalize() }.getOrNull()
                ?: return@readCancellable null
            if (!resolved.isAbsolute || !resolved.scheme.equals("https", ignoreCase = true) || resolved.userInfo != null) {
                return@readCancellable null
            }
            resolved.toString()
        }.also { epoch.ensureCurrent() }
    }

    /** Release asset 使用 uploads.github.com 的绝对 URL，仍复用统一鉴权和错误映射。 */
    suspend inline fun <reified T> uploadBytes(url: String, contentType: String, bytes: ByteArray): T {
        val epoch = AccountContext.ensureCurrent()
        val token = tokenProvider.getToken() ?: throw UnauthorizedException()
        epoch.ensureCurrent()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", DEFAULT_ACCEPT)
            .header("X-GitHub-Api-Version", REST_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        return json.decodeFromString(readUpload(request).decodeToString())
    }

    suspend inline fun <reified T> executeRest(method: String, path: String, body: String?, accept: String): T {
        val bytes = readRest(method, path, body, accept)
        if (bytes.isEmpty() && T::class == Unit::class) {
            @Suppress("UNCHECKED_CAST")
            return Unit as T
        }
        return json.decodeFromString(bytes.decodeToString())
    }

    suspend fun executeRestRaw(method: String, path: String, accept: String): String {
        return readRest(method, path, body = null, accept = accept).decodeToString()
    }

    // executeRest（public inline）直接调用这个函数，同样必须至少 internal 才能过编译，
    // 但它自己内部访问 restBaseUrl 这些私有成员没问题——它本身不是 inline，字节码不会被
    // 复制到别处，"泄漏"规则只约束 inline 函数体直接引用到的符号，不会顺着调用链传递。
    @PublishedApi
    internal suspend fun readRest(method: String, path: String, body: String?, accept: String): ByteArray {
        val epoch = AccountContext.ensureCurrent()
        val token = tokenProvider.getToken() ?: throw UnauthorizedException()
        epoch.ensureCurrent()
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

        return readUpload(request)
    }

    @PublishedApi
    internal suspend fun readUpload(request: Request): ByteArray {
        val epoch = AccountContext.ensureCurrent()
        return okHttpClient.newCall(request).readCancellable { response ->
            val bytes = response.body.bytes()
            if (!response.isSuccessful) throw HttpStatusException(response.code, bytes.decodeToString())
            bytes
        }.also { epoch.ensureCurrent() }
    }
}
