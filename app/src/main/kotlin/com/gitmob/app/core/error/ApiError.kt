package com.gitmob.app.core.error

import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * 统一错误分类。所有 Repository 方法失败时都归到这几类之一，
 * 不允许在各 Repository/ViewModel 里各自定义零散的异常类型。
 */
sealed class ApiError {
    data object Unauthorized : ApiError()      // 401，token 无效/过期
    data object Forbidden : ApiError()          // 403，权限/scope 不足
    data object RateLimited : ApiError()        // 429 或 GitHub rate limit 响应头耗尽
    data object NetworkError : ApiError()       // 超时/DNS 失败等，和凭证无关，不要误判为登录失效
    data class GraphQLError(val errors: List<GraphQLErrorItem>) : ApiError()

    /**
     * 业务层主动抛出的用户可见错误（"用户不存在"这类）。携带资源 id 而不是字符串，
     * 保证错误横幅文案跟随应用语言（i18n 改造：Repository 不再抛中文 message）。
     */
    data class UserVisible(@field:StringRes val messageRes: Int) : ApiError()

    /** 无法归类的异常。message 只进 Logcat，UI 一律显示通用"请求出错"文案。 */
    data class Unknown(val message: String) : ApiError()
}

@Serializable
data class GraphQLErrorItem(val message: String, val type: String? = null)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
}

class UnauthorizedException : Exception("Token invalid or expired")
class NetworkException(message: String) : Exception(message)
class GraphQLException(val errors: List<GraphQLErrorItem>) : Exception(errors.firstOrNull()?.message ?: "GraphQL request failed")

/**
 * Repository 层"数据不存在/操作失败"这类要给用户看的错误统一抛它，
 * safeCall 会映射成 [ApiError.UserVisible]；纯技术性断言继续用 IllegalStateException
 * （用户侧显示通用"请求出错"，细节只进 Logcat）。
 */
class UserVisibleException(@field:StringRes val messageRes: Int) : Exception()

// safeCall 是 public inline 函数，内部直接访问的常量必须至少是 internal；用 @PublishedApi 标注
// "仅供 inline 使用"，避免在外部被当作公开 API（Kotlin 官方推荐做法，等同于 GHApiClient 中 DEFAULT_ACCEPT 的处理）。
@PublishedApi internal const val TAG_SAFE_CALL = "GitMob-SafeCall"

/**
 * 统一异常 -> ApiResult 转换，所有 Repository 方法体外层包一层这个，
 * 不要各自写 try/catch 分类逻辑。
 * 所有捕获的异常都会打印 Logcat 堆栈，方便调试"未知错误"。
 */
suspend inline fun <T> safeCall(crossinline block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    // 协程取消属于正常控制流。筛选快速切换时旧请求会被主动取消，
    // 必须继续向上传播，不能落入 catch-all 后被误报为“未知错误”。
    throw e
} catch (e: UnauthorizedException) {
    Log.w(TAG_SAFE_CALL, "归类为 Unauthorized (401)", e)
    ApiResult.Failure(ApiError.Unauthorized)
} catch (e: UserVisibleException) {
    Log.w(TAG_SAFE_CALL, "归类为 UserVisible（业务层用户可见错误）", e)
    ApiResult.Failure(ApiError.UserVisible(e.messageRes))
} catch (e: GraphQLException) {
    Log.w(TAG_SAFE_CALL, "归类为 GraphQLError: ${e.errors}", e)
    ApiResult.Failure(ApiError.GraphQLError(e.errors))
} catch (e: HttpStatusException) {
    Log.w(TAG_SAFE_CALL, "HTTP 状态码异常: code=${e.code}, message=${e.message}", e)
    when (e.code) {
        401 -> ApiResult.Failure(ApiError.Unauthorized)
        403 -> ApiResult.Failure(ApiError.Forbidden)
        429 -> ApiResult.Failure(ApiError.RateLimited)
        else -> ApiResult.Failure(ApiError.Unknown("HTTP ${e.code}: ${e.message}"))
    }
} catch (e: NetworkException) {
    Log.w(TAG_SAFE_CALL, "归类为 NetworkException: ${e.message}", e)
    ApiResult.Failure(ApiError.NetworkError)
} catch (e: java.io.IOException) {
    Log.w(TAG_SAFE_CALL, "归类为 IOException (网络层 IO 失败)", e)
    ApiResult.Failure(ApiError.NetworkError)
} catch (e: android.os.NetworkOnMainThreadException) {
    // ⚠️ 这个异常说明某段同步阻塞代码（OkHttp execute()、数据库 rawQuery 等）被错误地
    // 放在了 Main 线程执行。按 Kotlin 协程"main-safe"规范，所有 suspend 函数都必须
    // 在内部用 withContext(Dispatchers.IO/Default) 自己保证不阻塞调用方。
    Log.e(TAG_SAFE_CALL, "检测到 NetworkOnMainThreadException！" +
            "说明有阻塞 I/O（网络/磁盘）跑在了 Main 线程。message=${e.message}", e)
    ApiResult.Failure(ApiError.Unknown("NetworkOnMainThreadException"))
} catch (e: Exception) {
    // 所有无法归类的异常统一打 ERROR 级别日志并带完整堆栈，
    // 这就是用户看到"请求出错"时定位问题的核心证据（UI 不再透传 message）。
    Log.e(TAG_SAFE_CALL, "未归类异常 -> 显示为通用请求出错。message=${e.message}, cause=${e.cause}", e)
    ApiResult.Failure(ApiError.Unknown(e.message ?: "unknown"))
}

class HttpStatusException(val code: Int, message: String) : Exception(message)
