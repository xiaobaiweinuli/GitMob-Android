package com.gitmob.app.core.auth

import com.gitmob.app.data.model.AccountAuthMethod

/** 普通 class 刻意不生成包含秘密的 toString。令牌对必须一起持久化。 */
class AccountCredentials(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long? = null,
    val refreshExpiresAtMillis: Long? = null,
    val authMethod: AccountAuthMethod = AccountAuthMethod.PAT,
)
