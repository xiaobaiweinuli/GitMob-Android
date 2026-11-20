package com.gitmob.app.data.model

enum class RepoForkSortField { CREATED, STARS, NAME, UPDATED, PUSHED }

enum class RepoForkSortDirection { ASC, DESC }

data class RepoForkSort(
    val field: RepoForkSortField = RepoForkSortField.CREATED,
    val direction: RepoForkSortDirection = RepoForkSortDirection.DESC,
)
