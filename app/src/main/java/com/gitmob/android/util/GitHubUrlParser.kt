package com.gitmob.android.util

import android.net.Uri

sealed class GitHubDestination {
    data class UserProfile(val login: String) : GitHubDestination()
    data class Repo(val owner: String, val repo: String, val tab: String? = null) : GitHubDestination()
    data class Issue(val owner: String, val repo: String, val number: Int) : GitHubDestination()
    data class PR(val owner: String, val repo: String, val number: Int) : GitHubDestination()
    data class Discussion(val owner: String, val repo: String, val number: Int) : GitHubDestination()
    data class Commit(val owner: String, val repo: String, val sha: String) : GitHubDestination()
    data class ActionRun(val owner: String, val repo: String, val runId: Long) : GitHubDestination()
    data class FileView(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    ) : GitHubDestination()
    object Home : GitHubDestination()
}

object GitHubUrlParser {
    /**
     * 解析 github.com URL，返回对应的导航目标。
     * 仅处理 https://github.com 下的路径，其余返回 Home。
     */
    fun parse(uri: Uri): GitHubDestination {
        if (uri.host != "github.com") return GitHubDestination.Home

        // pathSegments 会自动去掉空串（首尾斜杠）
        val segments = uri.pathSegments
        if (segments.isEmpty()) return GitHubDestination.Home

        // github.com/{username} 或 github.com/{orgname}
        if (segments.size == 1) {
            val login = segments[0]
            // 排除一些特殊路径
            if (login != "settings" && login != "organizations" && login != "explore" && 
                login != "topics" && login != "marketplace" && login != "about") {
                return GitHubDestination.UserProfile(login)
            }
            return GitHubDestination.Home
        }

        val owner = segments[0]
        val repo  = segments[1]

        // github.com/{owner}/{repo}
        if (segments.size == 2) return GitHubDestination.Repo(owner, repo)

        return when (segments[2]) {
            "issues" -> {
                val num = segments.getOrNull(3)?.toIntOrNull()
                if (num != null) GitHubDestination.Issue(owner, repo, num)
                else GitHubDestination.Repo(owner, repo, "issues")
            }
            "pull" -> {
                val num = segments.getOrNull(3)?.toIntOrNull()
                if (num != null) GitHubDestination.PR(owner, repo, num)
                else GitHubDestination.Repo(owner, repo, "pr")
            }
            "discussions" -> {
                val num = segments.getOrNull(3)?.toIntOrNull()
                if (num != null) GitHubDestination.Discussion(owner, repo, num)
                else GitHubDestination.Repo(owner, repo, "discussions")
            }
            "commits" -> {
                val sha = segments.getOrNull(3)
                if (sha != null) GitHubDestination.Commit(owner, repo, sha)
                else GitHubDestination.Repo(owner, repo, "commits")
            }
            "commit" -> {
                val sha = segments.getOrNull(3)
                if (sha != null) GitHubDestination.Commit(owner, repo, sha)
                else GitHubDestination.Repo(owner, repo, "commits")
            }
            "actions" -> {
                // 处理 actions/runs/{runId} 这种 URL
                if (segments.size >= 4 && segments[3] == "runs") {
                    val runId = segments.getOrNull(4)?.toLongOrNull()
                    if (runId != null) {
                        return GitHubDestination.ActionRun(owner, repo, runId)
                    }
                }
                GitHubDestination.Repo(owner, repo, "actions")
            }
            "releases" -> {
                GitHubDestination.Repo(owner, repo, "releases")
            }
            "tags" -> {
                GitHubDestination.Repo(owner, repo, "releases")
            }
            "branches" -> {
                GitHubDestination.Repo(owner, repo, "branches")
            }
            "blob", "tree" -> {
                val branch = segments.getOrNull(3) ?: return GitHubDestination.Repo(owner, repo)
                // segments[4..] 拼接为文件路径
                val path = segments.drop(4).joinToString("/")
                if (path.isNotEmpty()) {
                    GitHubDestination.FileView(owner, repo, branch, path)
                } else {
                    GitHubDestination.Repo(owner, repo)
                }
            }
            else -> GitHubDestination.Repo(owner, repo)
        }
    }
}
