package com.gitmob.app.core.network.graphql

import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloGraphQLException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.GraphQLErrorItem

/**
 * Apollo 异常 → 统一 [ApiError] 的唯一映射点（ADR：GraphQL 层替换方案 §6 R2）。
 * 与旧 GHApiClient 通道的分类语义保持一致：
 * 401→Unauthorized，403→Forbidden，429→RateLimited，网络层→NetworkError，
 * GraphQL errors 数组→GraphQLError，其余→Unknown。
 */
object GraphQLErrorMapper {

    fun toApiError(throwable: Throwable): ApiError = when (throwable) {
        is ApolloHttpException -> when (throwable.statusCode) {
            401 -> ApiError.Unauthorized
            403 -> ApiError.Forbidden
            429 -> ApiError.RateLimited
            else -> ApiError.Unknown("HTTP ${throwable.statusCode}: ${throwable.message}")
        }

        is ApolloNetworkException -> ApiError.NetworkError

        is ApolloGraphQLException -> ApiError.GraphQLError(
            listOfNotNull(throwable.error).map { error ->
                GraphQLErrorItem(
                    message = error.message,
                    type = error.extensions?.get("type")?.toString(),
                )
            },
        )

        is ApolloException -> ApiError.Unknown(throwable.message ?: "Apollo request failed")
        else -> ApiError.Unknown(throwable.message ?: "Unknown error")
    }

    /** Apollo execute() 不会把 GraphQL errors 抛异常（data/exception 并存），需显式检查 response。 */
    fun <T> toApiResult(
        data: T?,
        errors: List<com.apollographql.apollo.api.Error>?,
        exception: ApolloException?,
    ): ApiResult<T> {
        if (exception != null) return ApiResult.Failure(toApiError(exception))
        if (!errors.isNullOrEmpty()) {
            return ApiResult.Failure(
                ApiError.GraphQLError(
                    errors.map { GraphQLErrorItem(it.message, it.extensions?.get("type")?.toString()) },
                ),
            )
        }
        return if (data != null) {
            ApiResult.Success(data)
        } else {
            ApiResult.Failure(ApiError.Unknown("Empty response without errors"))
        }
    }
}
