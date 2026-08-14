package com.gitmob.app.core.auth

interface AccessTokenProvider {
    suspend fun getToken(): String?
}
