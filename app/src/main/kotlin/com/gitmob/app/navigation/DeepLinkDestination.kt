package com.gitmob.app.navigation

import android.net.Uri
import java.io.Serializable

/**
 * 外部 github.com 链接解析出的内部导航目的地。
 * 详见技能 references/deep-linking.md。
 */
sealed class DeepLinkDestination : Serializable {
    data class Profile(val login: String) : DeepLinkDestination()
    data class RepoOverview(val owner: String, val repo: String) : DeepLinkDestination()
    data class FileView(val owner: String, val repo: String, val ref: String, val path: String) : DeepLinkDestination()
    data class DirView(val owner: String, val repo: String, val ref: String, val path: String) : DeepLinkDestination()
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
        val segments = uri.pathSegments
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
