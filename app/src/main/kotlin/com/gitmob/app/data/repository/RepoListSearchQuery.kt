package com.gitmob.app.data.repository

import com.gitmob.app.data.model.*

private fun quoteSearchValue(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun searchLabels(labels: Set<String>): String? =
    labels.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = "label:", transform = ::quoteSearchValue)

internal fun repoIssueSearchQuery(owner: String, name: String, keywords: String, filter: RepoIssueFilter, login: String?): String =
    buildList {
        add("repo:$owner/$name")
        add("is:issue")
        if (keywords.isNotBlank()) add(searchExpression(keywords))
        when (filter.state) {
            RepoIssueStateFilter.OPEN -> add("is:open")
            RepoIssueStateFilter.CLOSED -> add("is:closed")
            RepoIssueStateFilter.ALL -> Unit
        }
        searchLabels(filter.labels)?.let(::add)
        when (val milestone = filter.milestone) {
            RepoMilestoneFilter.ALL -> Unit
            RepoMilestoneFilter.NONE -> add("no:milestone")
            is RepoMilestoneFilter.Title -> add("milestone:" + quoteSearchValue(milestone.title))
            is RepoMilestoneFilter.Number -> add("milestone:" + quoteSearchValue(milestone.title))
        }
        when (val assignee = filter.assignee) {
            RepoAssigneeFilter.ALL -> Unit
            RepoAssigneeFilter.ANY -> add("assignee:*")
            RepoAssigneeFilter.NONE -> add("no:assignee")
            is RepoAssigneeFilter.Login -> add("assignee:" + quoteSearchValue(assignee.value))
        }
        (filter.author as? RepoAuthorFilter.Login)?.let { add("author:" + quoteSearchValue(it.value)) }
        if (filter.mentionedMe) add("mentions:" + quoteSearchValue(checkNotNull(login)))
        filter.updatedSince?.let { add("updated:>=$it") }
        if (!hasSearchSort(keywords)) add("sort:" + when (filter.sort) {
            RepoIssueSort.UPDATED_DESC -> "updated-desc"
            RepoIssueSort.UPDATED_ASC -> "updated-asc"
            RepoIssueSort.CREATED_DESC -> "created-desc"
            RepoIssueSort.CREATED_ASC -> "created-asc"
            RepoIssueSort.COMMENTS_DESC -> "comments-desc"
            RepoIssueSort.COMMENTS_ASC -> "comments-asc"
        })
    }.joinToString(" ")

internal fun repoPullRequestSearchQuery(owner: String, name: String, keywords: String, filter: RepoPullRequestFilter): String =
    buildList {
        add("repo:$owner/$name")
        add("is:pr")
        if (keywords.isNotBlank()) add(searchExpression(keywords))
        when (filter.state) {
            RepoPullRequestStateFilter.OPEN -> add("is:open")
            RepoPullRequestStateFilter.CLOSED -> add("is:closed")
            RepoPullRequestStateFilter.ALL -> Unit
        }
        searchLabels(filter.labels)?.let(::add)
        if (!hasSearchSort(keywords)) add("sort:" + when (filter.sort) {
            RepoPullRequestSort.UPDATED_DESC -> "updated-desc"
            RepoPullRequestSort.UPDATED_ASC -> "updated-asc"
            RepoPullRequestSort.CREATED_DESC -> "created-desc"
            RepoPullRequestSort.CREATED_ASC -> "created-asc"
            RepoPullRequestSort.COMMENTS_DESC -> "comments-desc"
            RepoPullRequestSort.COMMENTS_ASC -> "comments-asc"
        })
    }.joinToString(" ")

internal fun repoDiscussionSearchQuery(owner: String, name: String, keywords: String, filter: RepoDiscussionFilter): String =
    buildList {
        add("repo:$owner/$name")
        if (keywords.isNotBlank()) add(keywords.trim())
        when (filter.state) {
            RepoDiscussionStateFilter.OPEN -> add("is:open")
            RepoDiscussionStateFilter.CLOSED -> add("is:closed")
            RepoDiscussionStateFilter.ALL -> Unit
        }
        (filter.category?.name ?: filter.categoryTitle)?.let { add("category:" + quoteSearchValue(it)) }
        filter.answered?.let { add(if (it) "is:answered" else "is:unanswered") }
        if (!hasSearchSort(keywords)) add("sort:" + when (filter.sort) {
            RepoDiscussionSort.UPDATED_DESC -> "updated-desc"
            RepoDiscussionSort.UPDATED_ASC -> "updated-asc"
            RepoDiscussionSort.CREATED_DESC -> "date_created-desc"
            RepoDiscussionSort.CREATED_ASC -> "date_created-asc"
        })
    }.joinToString(" ")
