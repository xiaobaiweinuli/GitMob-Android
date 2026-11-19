package com.gitmob.app.data.repository

import com.gitmob.app.data.model.*
import java.time.Instant

/** Keep quoted phrases and comma-separated quoted label values together. Boolean groups stay opaque. */
private fun queryTokens(query: String): List<String> = buildList {
    var start = 0
    var quoted = false
    var escaped = false
    for ((index, char) in query.withIndex()) {
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            !quoted && (char.isWhitespace() || char == '(' || char == ')') -> {
                if (index > start) add(query.substring(start, index))
                if (!char.isWhitespace()) add(char.toString())
                start = index + 1
            }
        }
    }
    if (start < query.length) add(query.substring(start))
}

private val quotedValue = Regex(""""((?:\\.|[^"\\])*)"""")
private fun String.searchValue(): String? {
    if (isEmpty()) return null
    if ('"' !in this) return this
    return quotedValue.matchEntire(this)?.groupValues?.get(1)?.replace(Regex("""\\(["\\])"""), "$1")
}

private class ListQuery(val source: String) {
    val tokens = queryTokens(source)
    val hasBooleanOperator = tokens.any { it == "OR" || it == "AND" || it == "NOT" }
    val grouped = hasBooleanOperator || tokens.any { it == "(" || it == ")" }
    private val remaining = tokens.toMutableList()

    fun field(key: String, apply: (String) -> Boolean) {
        if (grouped) return
        val term = remaining.filter { it.substringBefore(':').equals(key, true) && ':' in it }.singleOrNull() ?: return
        if (apply(term.substringAfter(':'))) remaining.remove(term)
    }

    fun flags(vararg values: String): String? {
        val term = remaining.filter { it.lowercase() in values }.singleOrNull() ?: return null
        if (grouped) return null
        remaining.remove(term)
        return term.lowercase()
    }

    fun hasValueAndAbsence(key: String) = tokens.any { it.startsWith("$key:", true) } &&
        tokens.any { it.equals("no:$key", true) }

    fun consumeScope(owner: String, name: String, type: String) {
        field("repo") { it.searchValue().equals("$owner/$name", true) }
        flags("is:$type", "type:$type")
    }
    fun state(): String? = flags("is:open", "state:open", "is:closed", "state:closed")?.substringAfter(':')
    fun labels(): Set<String>? {
        var labels: Set<String>? = null
        field("label") { value ->
            val pieces = Regex("""(?:"(?:\\.|[^"\\])*"|[^,\s"]+)""").findAll(value).map { it.value }.toList()
            if (pieces.isEmpty() || pieces.joinToString(",") != value) false else {
                labels = pieces.map { it.searchValue() ?: return@field false }.toSet()
                true
            }
        }
        return labels
    }
    inline fun <reified T : Enum<T>> sort(): T? {
        var sort: T? = null
        field("sort") { v ->
            sort = enumValues<T>().find { it.name.equals(v.replace("date_created-", "created-").replace('-', '_'), true) }
            sort != null
        }
        return sort
    }
    fun text() = if (grouped) source.trim() else remaining.joinToString(" ")
    fun hasStateExpression() = tokens.any { it.lowercase().removePrefix("-") in stateTerms }
}
private val stateTerms = setOf("is:open", "state:open", "is:closed", "state:closed", "is:merged", "is:unmerged")

internal data class ListSearch<F>(val text: String, val filter: F)

internal fun issueListSearch(text: String, owner: String, name: String, base: RepoIssueFilter): ListSearch<RepoIssueFilter> {
    val q = ListQuery(text)
    q.consumeScope(owner, name, "issue")
    var f = base.copy(state = when (q.state()) {
        "open" -> RepoIssueStateFilter.OPEN
        "closed" -> RepoIssueStateFilter.CLOSED
        else -> if (q.hasStateExpression()) RepoIssueStateFilter.ALL else base.state
    }, labels = q.labels() ?: base.labels, sort = q.sort<RepoIssueSort>() ?: base.sort)
    if (!q.hasValueAndAbsence("milestone")) {
        q.field("milestone") { v -> v.searchValue()?.let { f = f.copy(milestone = RepoMilestoneFilter.Title(it)) } != null }
        if (q.flags("no:milestone") != null) f = f.copy(milestone = RepoMilestoneFilter.NONE)
    }
    if (!q.hasValueAndAbsence("assignee")) {
        q.field("assignee") { v -> v.searchValue()?.let { f = f.copy(assignee = if (it == "*") RepoAssigneeFilter.ANY else RepoAssigneeFilter.Login(it)) } != null }
        if (q.flags("no:assignee") != null) f = f.copy(assignee = RepoAssigneeFilter.NONE)
    }
    q.field("author") { v -> v.searchValue()?.let { f = f.copy(author = RepoAuthorFilter.Login(it)) } != null }
    if (q.flags("mentions:@me") != null) f = f.copy(mentionedMe = true)
    q.field("updated") { v ->
        val since = v.takeIf { it.startsWith(">=") }?.let { runCatching { Instant.parse(it.drop(2)) }.getOrNull() }
        if (since != null) f = f.copy(updatedSince = since)
        since != null
    }
    return ListSearch(q.text(), f)
}

internal fun pullListSearch(text: String, owner: String, name: String, base: RepoPullRequestFilter): ListSearch<RepoPullRequestFilter> {
    val q = ListQuery(text)
    q.consumeScope(owner, name, "pr")
    val state = when (q.state()) {
        "open" -> RepoPullRequestStateFilter.OPEN
        "closed" -> RepoPullRequestStateFilter.CLOSED
        else -> if (q.hasStateExpression()) RepoPullRequestStateFilter.ALL else base.state
    }
    val filter = base.copy(state = state, labels = q.labels() ?: base.labels, sort = q.sort<RepoPullRequestSort>() ?: base.sort)
    return ListSearch(q.text(), filter)
}

internal fun discussionListSearch(text: String, owner: String, name: String, base: RepoDiscussionFilter): ListSearch<RepoDiscussionFilter> {
    val q = ListQuery(text)
    q.consumeScope(owner, name, "discussion")
    val state = when (q.state()) {
        "open" -> RepoDiscussionStateFilter.OPEN
        "closed" -> RepoDiscussionStateFilter.CLOSED
        else -> if (q.hasStateExpression()) RepoDiscussionStateFilter.ALL else base.state
    }
    var f = base.copy(state = state, sort = q.sort<RepoDiscussionSort>() ?: base.sort)
    q.field("category") { v -> v.searchValue()?.let { f = f.copy(category = null, categoryTitle = it) } != null }
    q.flags("is:answered", "is:unanswered")?.let { f = f.copy(answered = it == "is:answered") }
    return ListSearch(q.text(), f)
}

/** Prevent a user-supplied scope or an unbalanced group from escaping the repository/type prefix. */
internal fun validListSearch(text: String, owner: String, name: String, type: String): Boolean {
    val q = ListQuery(text)
    var depth = 0
    for (token in q.tokens) {
        when (token) {
            "(" -> depth++
            ")" -> if (--depth < 0) return false
        }
        if (token.startsWith('"') && token.searchValue() == null) return false
        val key = token.substringBefore(':').lowercase()
        if (':' !in token) continue
        val value = token.substringAfter(':').searchValue()?.lowercase()
        if (key == "repo" && !value.equals("$owner/$name", true)) return false
        if (key in setOf("-repo", "org", "-org", "user", "-user")) return false
        if (key in setOf("is", "type", "-is", "-type") && value in setOf("issue", "pr", "discussion") &&
            (value != type || key.startsWith('-'))) return false
    }
    return depth == 0 && (type != "discussion" || !q.hasBooleanOperator)
}

internal fun searchExpression(text: String): String {
    val trimmed = text.trim()
    return if (ListQuery(trimmed).grouped) "($trimmed)" else trimmed
}

internal fun hasSearchSort(text: String) = ListQuery(text).tokens.any { it.startsWith("sort:", ignoreCase = true) }
