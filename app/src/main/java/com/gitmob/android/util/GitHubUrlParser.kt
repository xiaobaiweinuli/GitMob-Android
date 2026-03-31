package com.gitmob.android.util

import android.net.Uri

sealed class GitHubDestination {
    data class Repo(val owner: String, val repo: String) : GitHubDestination()
    data class Issue(val owner: String, val repo: String, val number: Int) : GitHubDestination()
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
        if (segments.size < 2) return GitHubDestination.Home

        val owner = segments[0]
        val repo  = segments[1]

        // github.com/{owner}/{repo}
        if (segments.size == 2) return GitHubDestination.Repo(owner, repo)

        return when (segments[2]) {
            "issues" -> {
                val num = segments.getOrNull(3)?.toIntOrNull()
                if (num != null) GitHubDestination.Issue(owner, repo, num)
                else GitHubDestination.Repo(owner, repo)
            }
            "pull" -> {
                // 无独立 PR 详情页，回退到仓库详情
                GitHubDestination.Repo(owner, repo)
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
