package com.gitmob.app.data.model

/**
 * 解析 REST 通知 API 返回的 subject.url（形如
 * "https://api.github.com/repos/{owner}/{repo}/issues/123"），
 * 拿出 owner/repo/number 用于跳转到我们自己的详情页。
 *
 * 这个 URL 是 GitHub REST API 的资源地址，不是网页链接，issues 和 pulls
 * 在这个 URL 路径里都用 "issues" 这个词（GitHub 内部 PR 是 Issue 的特化），
 * 区分 Issue 还是 PR 得看 InboxNotification.subjectType 字段，不能靠这个 URL。
 */
data class InboxSubjectRef(val owner: String, val repo: String, val number: Int)

fun parseInboxSubjectUrl(url: String): InboxSubjectRef? {
    // .../repos/{owner}/{repo}/issues/{number} 或 .../repos/{owner}/{repo}/discussions/{number}
    val regex = Regex("""/repos/([^/]+)/([^/]+)/(?:issues|discussions|pulls)/(\d+)$""")
    val match = regex.find(url) ?: return null
    val (owner, repo, numberStr) = match.destructured
    return InboxSubjectRef(owner, repo, numberStr.toIntOrNull() ?: return null)
}
