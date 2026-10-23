package com.gitmob.app.core.network.graphql

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.exception.ApolloGraphQLException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B0 补测试：GraphQLErrorMapper 全错误分支覆盖。
 * 对齐旧 GHApiClient 通道的分类语义（401/403/429/网络/GraphQL errors）。
 */
class GraphQLErrorMapperTest {

    private fun httpException(statusCode: Int): ApolloHttpException =
        ApolloHttpException(
            statusCode = statusCode,
            headers = emptyList(),
            body = null,
            message = "HTTP $statusCode",
        )

    @Test
    fun `http 401 maps to Unauthorized`() {
        assertEquals(ApiError.Unauthorized, GraphQLErrorMapper.toApiError(httpException(401)))
    }

    @Test
    fun `http 403 maps to Forbidden`() {
        assertEquals(ApiError.Forbidden, GraphQLErrorMapper.toApiError(httpException(403)))
    }

    @Test
    fun `http 429 maps to RateLimited`() {
        assertEquals(ApiError.RateLimited, GraphQLErrorMapper.toApiError(httpException(429)))
    }

    @Test
    fun `http 500 maps to Unknown`() {
        val error = GraphQLErrorMapper.toApiError(httpException(500))
        assertTrue(error is ApiError.Unknown)
    }

    @Test
    fun `network failure maps to NetworkError`() {
        val error = GraphQLErrorMapper.toApiError(ApolloNetworkException("timeout"))
        assertEquals(ApiError.NetworkError, error)
    }

    @Test
    fun `graphql exception maps to GraphQLError with items`() {
        val gqlError = Error.Builder("Field 'viewer' doesn't exist")
            .apply { extensions.put("type", "INVALID_FIELD") }
            .build()
        val error = GraphQLErrorMapper.toApiError(ApolloGraphQLException(listOf(gqlError)))
        assertTrue(error is ApiError.GraphQLError)
        val items = (error as ApiError.GraphQLError).errors
        assertEquals(1, items.size)
        assertEquals("Field 'viewer' doesn't exist", items[0].message)
        assertEquals("INVALID_FIELD", items[0].type)
    }

    @Test
    fun `toApiResult returns Success when data present`() {
        val result = GraphQLErrorMapper.toApiResult(data = "ok", errors = null, exception = null)
        assertEquals(ApiResult.Success("ok"), result)
    }

    @Test
    fun `toApiResult returns Failure when errors present even with data`() {
        val gqlError = Error.Builder("rate limited").build()
        val result = GraphQLErrorMapper.toApiResult(
            data = "partial",
            errors = listOf(gqlError),
            exception = null,
        )
        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.GraphQLError)
    }

    @Test
    fun `toApiResult returns Failure when data and errors both null`() {
        val result = GraphQLErrorMapper.toApiResult(data = null, errors = null, exception = null)
        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Unknown)
    }

    @Test
    fun `toApiResult prefers exception over errors`() {
        val result = GraphQLErrorMapper.toApiResult(
            data = null,
            errors = listOf(Error.Builder("ignored").build()),
            exception = ApolloNetworkException("offline"),
        )
        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.NetworkError, (result as ApiResult.Failure).error)
    }
}
