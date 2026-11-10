package com.gitmob.app.core.network.graphql

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.network.USER_AGENT

/** Apollo 鉴权头注入与 HTTP 401 处理；普通 GraphQL 字段错误不改变账号状态。 */
class AuthHttpInterceptor(
    private val tokenProvider: AccessTokenProvider,
) : HttpInterceptor {
    override suspend fun intercept(
        request: HttpRequest,
        chain: HttpInterceptorChain,
    ): HttpResponse {
        val epoch = AccountContext.ensureCurrent()
        val builder = request.newBuilder()
            .addHeader("User-Agent", USER_AGENT)
        val token = tokenProvider.getToken()
        if (token != null) {
            builder.addHeader("Authorization", "Bearer $token")
        }
        epoch.ensureCurrent()
        val response = chain.proceed(builder.build())
        try {
            epoch.ensureCurrent()
            if (response.statusCode == 401 && token != null) tokenProvider.onUnauthorized(token, epoch)
            epoch.ensureCurrent()
            return response
        } catch (e: Exception) {
            response.body?.close()
            throw e
        }
    }
}
