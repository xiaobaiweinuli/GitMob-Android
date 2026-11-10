package com.gitmob.app.testutil

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.auth.AccountEpoch

/** 固定凭据的网络测试替身；账号失效行为由 SessionTokenProvider 的集成测试覆盖。 */
interface TestAccessTokenProvider : AccessTokenProvider {
    override suspend fun onUnauthorized(token: String, epoch: AccountEpoch) = Unit
}
