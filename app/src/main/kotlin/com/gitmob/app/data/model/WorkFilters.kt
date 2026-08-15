package com.gitmob.app.data.model

enum class UserIssueStateFilter { OPEN, CLOSED, ALL }

enum class UserIssueRelationFilter { INVOLVED, AUTHORED, ASSIGNED, MENTIONED, COMMENTED }

enum class UserIssueVisibilityFilter { ALL, PUBLIC, PRIVATE, INTERNAL }

enum class UserIssueSortFilter {
    CREATED_DESC,
    CREATED_ASC,
    COMMENTS_DESC,
    COMMENTS_ASC,
    UPDATED_DESC,
    UPDATED_ASC,
}

data class UserIssueFilter(
    val state: UserIssueStateFilter = UserIssueStateFilter.OPEN,
    val relation: UserIssueRelationFilter = UserIssueRelationFilter.INVOLVED,
    val visibility: UserIssueVisibilityFilter = UserIssueVisibilityFilter.ALL,
    val sort: UserIssueSortFilter = UserIssueSortFilter.UPDATED_DESC,
)

enum class UserPullRequestStateFilter { OPEN, MERGED, CLOSED_UNMERGED, ALL }

enum class UserPullRequestRelationFilter { INVOLVED, AUTHORED, ASSIGNED, REVIEW_REQUESTED, COMMENTED }

enum class UserPullRequestVisibilityFilter { ALL, PUBLIC, PRIVATE, INTERNAL }

enum class UserPullRequestSortFilter {
    CREATED_DESC,
    CREATED_ASC,
    COMMENTS_DESC,
    COMMENTS_ASC,
    UPDATED_DESC,
    UPDATED_ASC,
}

data class UserPullRequestFilter(
    val state: UserPullRequestStateFilter = UserPullRequestStateFilter.OPEN,
    val relation: UserPullRequestRelationFilter = UserPullRequestRelationFilter.INVOLVED,
    val visibility: UserPullRequestVisibilityFilter = UserPullRequestVisibilityFilter.ALL,
    val sort: UserPullRequestSortFilter = UserPullRequestSortFilter.UPDATED_DESC,
)

enum class UserDiscussionStateFilter { ALL, OPEN, CLOSED }

enum class UserDiscussionRelationFilter { INVOLVED, AUTHORED, COMMENTED }

enum class UserDiscussionAnswerFilter { ALL, ANSWERED, UNANSWERED }

enum class UserDiscussionVisibilityFilter { ALL, PUBLIC, PRIVATE, INTERNAL }

enum class UserDiscussionSortFilter { CREATED_DESC, CREATED_ASC, UPDATED_DESC, UPDATED_ASC }

data class UserDiscussionFilter(
    val state: UserDiscussionStateFilter = UserDiscussionStateFilter.ALL,
    val relation: UserDiscussionRelationFilter = UserDiscussionRelationFilter.INVOLVED,
    val answer: UserDiscussionAnswerFilter = UserDiscussionAnswerFilter.ALL,
    val visibility: UserDiscussionVisibilityFilter = UserDiscussionVisibilityFilter.ALL,
    val sort: UserDiscussionSortFilter = UserDiscussionSortFilter.UPDATED_DESC,
)
