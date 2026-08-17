package com.gitmob.app.data.model

import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import java.time.Instant

enum class RepoIssueStateFilter { OPEN, CLOSED, ALL }

enum class RepoIssueSort { UPDATED_DESC, UPDATED_ASC, CREATED_DESC, CREATED_ASC, COMMENTS_DESC, COMMENTS_ASC }

sealed interface RepoMilestoneFilter {
    data object ALL : RepoMilestoneFilter
    data object NONE : RepoMilestoneFilter
    data class Number(val value: Int) : RepoMilestoneFilter
}

sealed interface RepoAssigneeFilter {
    data object ALL : RepoAssigneeFilter
    data object ANY : RepoAssigneeFilter
    data object NONE : RepoAssigneeFilter
    data class Login(val value: String) : RepoAssigneeFilter
}

sealed interface RepoAuthorFilter {
    data object ALL : RepoAuthorFilter
    data class Login(val value: String) : RepoAuthorFilter
}

data class RepoIssueFilter(
    val state: RepoIssueStateFilter = RepoIssueStateFilter.OPEN,
    val sort: RepoIssueSort = RepoIssueSort.UPDATED_DESC,
    val labels: Set<String> = emptySet(),
    val milestone: RepoMilestoneFilter = RepoMilestoneFilter.ALL,
    val assignee: RepoAssigneeFilter = RepoAssigneeFilter.ALL,
    val author: RepoAuthorFilter = RepoAuthorFilter.ALL,
    val mentioned: Boolean = false,
    val subscribed: Boolean = false,
    val updatedSince: Instant? = null,
)

data class IssueLabel(val id: String, val name: String, val color: String, val description: String?)

data class IssueMilestone(
    val id: String,
    val number: Int,
    val title: String,
    val state: String,
    val dueOn: String?,
)

data class IssueTemplate(
    val name: String,
    val about: String?,
    val title: String?,
    val filename: String,
    val labels: List<String>,
    val assignees: List<String>,
    val fields: List<IssueFormField>,
)

sealed interface IssueFormField {
    val id: String?

    data class Markdown(
        override val id: String? = null,
        val value: String,
    ) : IssueFormField

    data class Input(
        override val id: String,
        val label: String,
        val description: String? = null,
        val placeholder: String? = null,
        val value: String? = null,
        val required: Boolean = false,
    ) : IssueFormField

    data class Textarea(
        override val id: String,
        val label: String,
        val description: String? = null,
        val placeholder: String? = null,
        val value: String? = null,
        val render: String? = null,
        val required: Boolean = false,
    ) : IssueFormField

    data class Dropdown(
        override val id: String,
        val label: String,
        val description: String? = null,
        val options: List<String>,
        val multiple: Boolean = false,
        val defaultIndex: Int? = null,
        val required: Boolean = false,
    ) : IssueFormField

    data class Checkboxes(
        override val id: String,
        val label: String,
        val description: String? = null,
        val options: List<IssueFormCheckboxOption>,
    ) : IssueFormField
}

data class IssueFormCheckboxOption(
    val label: String,
    val required: Boolean = false,
)

data class IssueTemplateLoadResult(
    val blankIssuesEnabled: Boolean,
    val templates: List<IssueTemplate>,
    val invalidTemplateCount: Int = 0,
)

data class IssueComment(
    val id: String,
    val author: SimpleUser?,
    val body: String?,
    val bodyHtml: String,
    val createdAt: String,
    val updatedAt: String,
    val viewerDidAuthor: Boolean,
    val viewerCanUpdate: Boolean,
    val viewerCanDelete: Boolean,
    val viewerCanReact: Boolean,
)

data class RepoIssue(
    val id: String,
    val number: Int,
    val title: String,
    val body: String?,
    val bodyHtml: String?,
    val state: IssueState,
    val stateReason: IssueStateReason?,
    val author: SimpleUser?,
    val createdAt: String,
    val updatedAt: String,
    val commentCount: Int,
    val labels: List<IssueLabel>,
    val assignees: List<SimpleUser>,
    val milestone: IssueMilestone?,
    val locked: Boolean,
    val viewerCanClose: Boolean,
    val viewerCanDelete: Boolean,
    val viewerCanLabel: Boolean,
    val viewerCanSetMilestone: Boolean,
    val viewerCanUpdate: Boolean,
    val viewerCanSubscribe: Boolean,
    val viewerCanReopen: Boolean,
    val viewerSubscription: String?,
)

data class RepoIssuePage(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val viewerCanCreateIssues: Boolean,
    val hasIssuesEnabled: Boolean,
    val totalCount: Int,
    val items: List<RepoIssue>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

data class IssueCommentPage(
    val items: List<IssueComment>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

data class RepoIssueDetail(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val issue: RepoIssue,
    val comments: IssueCommentPage,
)

data class CreateRepoIssueInput(
    val repositoryId: String,
    val title: String,
    val body: String,
    val labelIds: List<String> = emptyList(),
    val assigneeIds: List<String> = emptyList(),
    val milestoneId: String? = null,
)

data class UpdateRepoIssueInput(
    val id: String,
    val title: String,
    val body: String,
    val labelIds: List<String> = emptyList(),
    val assigneeIds: List<String> = emptyList(),
    val milestoneId: String? = null,
)
