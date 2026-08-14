package com.gitmob.app.data.model

data class InboxNotification(
    val id: String,
    val repoOwner: String,
    val repoName: String,
    val title: String,
    /** "Issue"/"PullRequest"/"Discussion"/"Release"/"Commit"/"CheckSuite" 等，来自 REST thread.subject.type */
    val subjectType: String,
    /** REST 返回的是 API URL（比如 .../repos/o/r/issues/123），要跳转到我们自己的详情页
     *  得从这里解析出 number，见 references/api-verification.md 的解析规则 */
    val subjectApiUrl: String,
    val reason: String,
    val isUnread: Boolean,
    val updatedAt: String,
)
