package com.gitmob.app.core.network.graphql

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.network.USER_AGENT

/**
 * Apollo 通道的鉴权/UA 头注入。对齐 GHApiClient 内 GraphQL 分支的头语义：
 * Authorization: Bearer <token>（有 token 时）、User-Agent 强制携带。
 * token 获取是 suspend，Apollo HttpInterceptor 的 intercept 同为 suspend，天然适配。
 */
class AuthHttpInterceptor(
    private val tokenProvider: AccessTokenProvider,
) : HttpInterceptor {
    override suspend fun intercept(
        request: HttpRequest,
        chain: HttpInterceptorChain,
    ): HttpResponse {
        val builder = request.newBuilder()
            .addHeader("User-Agent", USER_AGENT)
        tokenProvider.getToken()?.let { token ->
            builder.addHeader("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }
}
