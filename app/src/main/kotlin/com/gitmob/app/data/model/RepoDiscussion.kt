package com.gitmob.app.data.model

import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission

enum class RepoDiscussionState { OPEN, CLOSED }
enum class RepoDiscussionStateFilter { OPEN, CLOSED, ALL }
enum class RepoDiscussionSort { UPDATED_DESC, UPDATED_ASC, CREATED_DESC, CREATED_ASC }

data class RepoDiscussionCategory(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String?,
    val isAnswerable: Boolean,
)

data class RepoDiscussion(
    val id: String,
    val number: Int,
    val title: String,
    val body: String,
    val bodyHtml: String,
    val state: RepoDiscussionState,
    val category: RepoDiscussionCategory,
    val author: SimpleUser?,
    val createdAt: String,
    val updatedAt: String,
    val commentCount: Int,
    val labels: List<IssueLabel>,
    val locked: Boolean,
    val answerChosenAt: String?,
    val viewerCanUpdate: Boolean,
    val viewerCanDelete: Boolean,
    val viewerCanClose: Boolean,
    val viewerCanReopen: Boolean,
    val viewerCanSubscribe: Boolean,
    val viewerSubscription: String?,
    val url: String = "",
    val authorAssociation: CommentAuthorAssociation = CommentAuthorAssociation.NONE,
)

data class RepoDiscussionComment(
    val id: String,
    val body: String,
    val bodyHtml: String,
    val author: SimpleUser?,
    val createdAt: String,
    val updatedAt: String,
    val isAnswer: Boolean,
    val replyToId: String?,
    val viewerCanUpdate: Boolean,
    val viewerCanDelete: Boolean,
    val viewerCanReact: Boolean,
    val viewerCanMarkAsAnswer: Boolean,
    val viewerCanUnmarkAsAnswer: Boolean,
    val url: String = "",
    val authorAssociation: CommentAuthorAssociation = CommentAuthorAssociation.NONE,
)

data class RepoDiscussionFilter(
    val state: RepoDiscussionStateFilter = RepoDiscussionStateFilter.OPEN,
    val sort: RepoDiscussionSort = RepoDiscussionSort.UPDATED_DESC,
    val categoryId: String? = null,
    val answered: Boolean? = null,
)

data class RepoDiscussionPage(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val hasDiscussionsEnabled: Boolean,
    val categories: List<RepoDiscussionCategory>,
    val totalCount: Int,
    val items: List<RepoDiscussion>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

data class RepoDiscussionDetail(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val categories: List<RepoDiscussionCategory>,
    val discussion: RepoDiscussion,
    val comments: List<RepoDiscussionComment>,
    val hasNextComments: Boolean,
    val commentsEndCursor: String?,
)
