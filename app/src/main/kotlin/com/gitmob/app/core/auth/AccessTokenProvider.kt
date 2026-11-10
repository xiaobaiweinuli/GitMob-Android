package com.gitmob.app.core.auth

interface AccessTokenProvider {
    suspend fun getToken(): String?

    /** 仅报告携带该令牌的请求返回的 HTTP 401；实现须校验会话与令牌仍匹配。 */
    suspend fun onUnauthorized(token: String, epoch: AccountEpoch)
}
