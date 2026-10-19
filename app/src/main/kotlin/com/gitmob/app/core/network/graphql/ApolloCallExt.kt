package com.gitmob.app.core.network.graphql

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.api.Operation
import com.gitmob.app.core.error.ApiResult
import kotlinx.coroutines.CancellationException

/**
 * Apollo 调用的统一出口：execute() + 错误归一。
 * - Apollo 4 中网络/HTTP/解析异常收在 response.exception（不抛出），但仍兜底 try/catch；
 * - GraphQL errors 数组与 data 可能并存，统一交给 GraphQLErrorMapper 判定；
 * - 协程取消必须继续向上抛，不能被归类为错误。
 *
 * 业务映射（UserVisibleException 等）仍由调用方在拿到 Success 后用 safeCall 包裹。
 */
suspend fun <D : Operation.Data> ApolloCall<D>.executeAsApiResult(): ApiResult<D> = try {
    val response = execute()
    GraphQLErrorMapper.toApiResult(response.data, response.errors, response.exception)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ApiResult.Failure(GraphQLErrorMapper.toApiError(e))
}
