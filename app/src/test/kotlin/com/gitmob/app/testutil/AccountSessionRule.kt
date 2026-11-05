package com.gitmob.app.testutil

import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.data.model.GitHubAccount
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** 每个测试显式绑定身份，并在结束时取消会话及其 ViewModel 工作。 */
class AccountSessionRule(
    private val account: GitHubAccount? = GitHubAccount("1", 1, "U_1", "viewer"),
) : TestWatcher() {
    val session = AccountSession()
    val context get() = AccountContext(session.epoch)

    override fun starting(description: Description) {
        account?.let { session.replace(listOf(it), it.key) }
    }

    override fun finished(description: Description) {
        session.epoch.job.cancel()
    }
}
