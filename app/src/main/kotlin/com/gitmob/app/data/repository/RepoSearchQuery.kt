package com.gitmob.app.data.repository

import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoSort
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType

/** List search is plain text; only the controls may add search qualifiers. */
internal fun repoSearchQuery(owner: RepositoryCreateOwner, filters: RepoFilters): String = buildList {
    val scope = if (owner.type == RepositoryCreateOwnerType.ORGANIZATION) "org" else "user"
    add("$scope:${owner.login}")
    val normalized = filters.normalized()
    if (normalized.query.isNotEmpty()) {
        val literal = normalized.query.replace("\\", "\\\\").replace("\"", "\\\"")
        add("\"$literal\"")
        add("in:name,description")
    }
    when (normalized.visibility) {
        RepoVisibility.PUBLIC -> add("is:public")
        RepoVisibility.PRIVATE -> add("is:private")
        else -> Unit
    }
    normalized.isArchived?.let { add("archived:$it") }
    add(when (normalized.isFork) {
        null -> "fork:true"
        false -> "fork:false"
        true -> "fork:only"
    })
    add("sort:" + when (normalized.sort) {
        RepoSort.UPDATED_DESC -> "updated-desc"
        RepoSort.UPDATED_ASC -> "updated-asc"
        RepoSort.STARS_ASC -> "stars-asc"
        RepoSort.STARS_DESC -> "stars-desc"
        RepoSort.NAME_DESC -> "name-desc"
        RepoSort.NAME_ASC -> "name-asc"
    })
}.joinToString(" ")
