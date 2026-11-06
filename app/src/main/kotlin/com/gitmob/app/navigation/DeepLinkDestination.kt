package com.gitmob.app.navigation

import android.net.Uri
import java.io.Serializable

/**
 * 外部 GitHub / Gist 链接解析出的内部导航目的地。
 * 详见技能 references/deep-linking.md。
 */
sealed class DeepLinkDestination : Serializable {
    data class GistDetail(val id: String) : DeepLinkDestination()
    data object BrowserUnavailable : DeepLinkDestination()
    data class Profile(val login: String) : DeepLinkDestination()
    data class RepoOverview(val owner: String, val repo: String) : DeepLinkDestination()
    data class FileView(val owner: String, val repo: String, val ref: String, val path: String) : DeepLinkDestination()
    data class DirView(val owner: String, val repo: String, val ref: String, val path: String) : DeepLinkDestination()
    data class CommitDetail(val owner: String, val repo: String, val sha: String) : DeepLinkDestination()
    data class CommitList(val owner: String, val repo: String, val ref: String, val path: String?) : DeepLinkDestination()
    data class IssueDetail(val owner: String, val repo: String, val number: Int) : DeepLinkDestination()
    data class IssueList(val owner: String, val repo: String) : DeepLinkDestination()
    data class PullRequestDetail(val owner: String, val repo: String, val number: Int) : DeepLinkDestination()
    data class PullRequestList(val owner: String, val repo: String) : DeepLinkDestination()
    data class DiscussionDetail(val owner: String, val repo: String, val number: Int) : DeepLinkDestination()
    data class DiscussionList(val owner: String, val repo: String) : DeepLinkDestination()
    data class Actions(val owner: String, val repo: String) : DeepLinkDestination()
    data class WorkflowRun(val owner: String, val repo: String, val runId: Long) : DeepLinkDestination()
    data class ReleaseList(val owner: String, val repo: String) : DeepLinkDestination()
    data class ReleaseDetail(val owner: String, val repo: String, val tag: String) : DeepLinkDestination()
    data class Contributors(val owner: String, val repo: String) : DeepLinkDestination()
    data object Unsupported : DeepLinkDestination()
}

object DeepLinkRouter {
    fun parse(uri: Uri): DeepLinkDestination {
        if (!uri.scheme.equals("https", ignoreCase = true) &&
            !uri.scheme.equals("http", ignoreCase = true)
        ) return DeepLinkDestination.Unsupported

        val segments = uri.pathSegments
        when {
            uri.host.equals("gist.github.com", ignoreCase = true) -> {
                val id = when (segments.size) {
                    1 -> segments[0]
                    2 -> segments[1]
                    else -> return DeepLinkDestination.Unsupported
                }
                return if (id.matches(Regex("[0-9a-fA-F]+"))) {
                    DeepLinkDestination.GistDetail(id)
                } else {
                    DeepLinkDestination.Unsupported
                }
            }
            !uri.host.equals("github.com", ignoreCase = true) -> return DeepLinkDestination.Unsupported
        }
        if (segments.isEmpty()) return DeepLinkDestination.Unsupported

        // https://github.com/orgs/{org} 特殊前缀，等价于组织主页
        if (segments.size >= 2 && segments[0] == "orgs") {
            return DeepLinkDestination.Profile(segments[1])
        }

        // https://github.com/{owner} —— 只有一段，是主页（用户或组织，运行时靠 repositoryOwner 查询判断）
        if (segments.size == 1) return DeepLinkDestination.Profile(segments[0])

        val owner = segments[0]
        val repo = segments.getOrNull(1) ?: return DeepLinkDestination.Unsupported

        if (segments.size == 2) return DeepLinkDestination.RepoOverview(owner, repo)

        return when (segments.getOrNull(2)) {
            "blob" -> {
                val ref = segments.getOrNull(3) ?: return DeepLinkDestination.Unsupported
                val path = segments.drop(4).joinToString("/")
                DeepLinkDestination.FileView(owner, repo, ref, path)
            }
            "tree" -> {
                val ref = segments.getOrNull(3) ?: return DeepLinkDestination.Unsupported
                val path = segments.drop(4).joinToString("/")
                DeepLinkDestination.DirView(owner, repo, ref, path)
            }
            "commit" -> {
                val sha = segments.getOrNull(3) ?: return DeepLinkDestination.Unsupported
                DeepLinkDestination.CommitDetail(owner, repo, sha)
            }
            "commits" -> {
                val ref = segments.getOrNull(3) ?: return DeepLinkDestination.Unsupported
                val path = segments.drop(4).joinToString("/").ifBlank { null }
                DeepLinkDestination.CommitList(owner, repo, ref, path)
            }
            "issues" -> {
                val number = segments.getOrNull(3)?.toIntOrNull()
                if (number != null) DeepLinkDestination.IssueDetail(owner, repo, number)
                else DeepLinkDestination.IssueList(owner, repo)
            }
            "pull" -> {
                val number = segments.getOrNull(3)?.toIntOrNull() ?: return DeepLinkDestination.Unsupported
                DeepLinkDestination.PullRequestDetail(owner, repo, number)
            }
            "pulls" -> DeepLinkDestination.PullRequestList(owner, repo)
            "discussions" -> {
                val number = segments.getOrNull(3)?.toIntOrNull()
                if (number != null) DeepLinkDestination.DiscussionDetail(owner, repo, number)
                else DeepLinkDestination.DiscussionList(owner, repo)
            }
            "actions" -> {
                val runId = if (segments.getOrNull(3) == "runs") segments.getOrNull(4)?.toLongOrNull() else null
                if (runId != null) DeepLinkDestination.WorkflowRun(owner, repo, runId)
                else DeepLinkDestination.Actions(owner, repo)
            }
            "releases" -> {
                val tag = if (segments.getOrNull(3) == "tag") segments.drop(4).joinToString("/") else ""
                if (tag.isNotBlank()) DeepLinkDestination.ReleaseDetail(owner, repo, tag)
                else DeepLinkDestination.ReleaseList(owner, repo)
            }
            "graphs" -> if (segments.getOrNull(3) == "contributors") {
                DeepLinkDestination.Contributors(owner, repo)
            } else {
                DeepLinkDestination.Unsupported
            }
            else -> DeepLinkDestination.Unsupported
        }
    }
}
