package com.gitmob.app.ui.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.gitmob.app.data.model.SearchCategory
import com.gitmob.app.data.repository.unquotedSearchTokens

internal data class SearchSyntax(
    val token: String, val value: String,
    val categories: Set<SearchCategory>, val singleOrganization: Boolean = false,
)

// Approved catalogue: repo-enhancement-ui/github-search/syntax-reference.md.
// Legacy REST code search only; this deliberately excludes web-only regex and symbol: syntax.
internal val searchSyntax = listOf(
    SearchSyntax("repo:owner/name", "owner/name", setOf(SearchCategory.CODE, SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("language:kotlin", "kotlin", setOf(SearchCategory.CODE, SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS, SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("org:github", "github", setOf(SearchCategory.CODE, SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS, SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("user:octocat", "octocat", setOf(SearchCategory.CODE, SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS, SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("is:issue", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:pr", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("type:user", "", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("type:org", "", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("stars:>100", ">100", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("is:open", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:closed", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("label:bug", "bug", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("author:@me", "@me", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("assignee:@me", "@me", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("path:src", "src", setOf(SearchCategory.CODE), false),
    SearchSyntax("filename:Search.kt", "Search.kt", setOf(SearchCategory.CODE), false),
    SearchSyntax("extension:kt", "kt", setOf(SearchCategory.CODE), false),
    SearchSyntax("topic:android", "android", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("location:China", "China", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("created:>2025-01-01", ">2025-01-01", setOf(SearchCategory.REPOSITORIES, SearchCategory.USERS, SearchCategory.ORGANIZATIONS, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("in:file", "", setOf(SearchCategory.CODE), false),
    SearchSyntax("in:path", "", setOf(SearchCategory.CODE), false),
    SearchSyntax("in:file,path", "", setOf(SearchCategory.CODE), false),
    SearchSyntax("size:>1000", ">1000", setOf(SearchCategory.CODE, SearchCategory.REPOSITORIES), false),
    SearchSyntax("fork:true", "", setOf(SearchCategory.CODE, SearchCategory.REPOSITORIES), false),
    SearchSyntax("fork:only", "", setOf(SearchCategory.CODE, SearchCategory.REPOSITORIES), false),
    SearchSyntax("in:name", "", setOf(SearchCategory.REPOSITORIES, SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("in:description", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("in:topics", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("in:readme", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("followers:>100", ">100", setOf(SearchCategory.REPOSITORIES, SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("forks:>10", ">10", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("stars:10..100", "10..100", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("pushed:>2025-01-01", ">2025-01-01", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("topics:>3", ">3", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("license:mit", "mit", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("is:public", "", setOf(SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:private", "", setOf(SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("props.environment:production", "production", setOf(SearchCategory.REPOSITORIES), true),
    SearchSyntax("mirror:true", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("mirror:false", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("template:true", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("template:false", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("archived:true", "", setOf(SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("archived:false", "", setOf(SearchCategory.REPOSITORIES, SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("good-first-issues:>0", ">0", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("help-wanted-issues:>0", ">0", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("is:sponsorable", "", setOf(SearchCategory.REPOSITORIES, SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("has:funding-file", "", setOf(SearchCategory.REPOSITORIES), false),
    SearchSyntax("in:title", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("in:body", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("in:comments", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("type:issue", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("type:pr", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("state:open", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("state:closed", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:archived", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:queued", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("reason:completed", "", setOf(SearchCategory.ISSUES), false),
    SearchSyntax("reason:\"not planned\"", "", setOf(SearchCategory.ISSUES), false),
    SearchSyntax("author:octocat", "octocat", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("author:app/copilot", "copilot", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("assignee:octocat", "octocat", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("assignee:*", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("mentions:octocat", "octocat", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("team:github/docs", "github/docs", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("commenter:@me", "@me", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("involves:@me", "@me", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("linked:pr", "", setOf(SearchCategory.ISSUES), false),
    SearchSyntax("linked:issue", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("-linked:pr", "", setOf(SearchCategory.ISSUES), false),
    SearchSyntax("-linked:issue", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("label:\"help wanted\"", "\"help wanted\"", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("label:bug,enhancement", "bug,enhancement", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("-label:bug", "bug", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("milestone:\"v1.0\"", "\"v1.0\"", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("project:github/57", "github/57", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("status:pending", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("status:success", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("status:failure", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("head:feature", "feature", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("base:main", "main", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("comments:>10", ">10", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("interactions:>10", ">10", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("reactions:>10", ">10", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("draft:true", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("draft:false", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("review:none", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("review:required", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("review:approved", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("review:changes_requested", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("reviewed-by:@me", "@me", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("review-requested:@me", "@me", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("review-involves:@me", "@me", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("user-review-requested:@me", "@me", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("team-review-requested:github/docs", "github/docs", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("team-review-requested-user:octocat", "octocat", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("updated:>2025-01-01", ">2025-01-01", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("closed:>2025-01-01", ">2025-01-01", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("merged:>2025-01-01", ">2025-01-01", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:merged", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:unmerged", "", setOf(SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:locked", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("is:unlocked", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("no:label", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("no:milestone", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("no:assignee", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("no:project", "", setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS), false),
    SearchSyntax("in:login", "", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("in:email", "", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("in:fullname", "", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("fullname:\"Nat Friedman\"", "\"Nat Friedman\"", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
    SearchSyntax("repos:>10", ">10", setOf(SearchCategory.USERS, SearchCategory.ORGANIZATIONS), false),
)

private fun completionRange(input: TextFieldValue): IntRange? {
    if (!input.selection.collapsed) return null
    val cursor = input.selection.end
    val start = input.text.take(cursor).indexOfLast(Char::isWhitespace) + 1
    val prefix = input.text.substring(start, cursor)
    if (prefix.isEmpty() || '"' in prefix) return null
    // Do not complete within a quoted phrase, including multiword phrases.
    if (input.text.take(start).count { it == '"' } % 2 != 0) return null
    if (searchSyntax.none { it.token.startsWith(prefix, ignoreCase = true) }) return null
    val end = input.text.indexOfFirstFrom(cursor) { it.isWhitespace() }
    return start until end
}

private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int =
    (start until length).firstOrNull { predicate(this[it]) } ?: length

internal fun syntaxSuggestions(input: TextFieldValue): List<SearchSyntax> {
    val organizations = unquotedSearchTokens(input.text).filter { it.startsWith("org:", true) }
        .map { it.substringAfter(':').lowercase() }.filter(String::isNotBlank).distinct()
    val range = completionRange(input)
    val prefix = range?.let { input.text.substring(it.first, input.selection.end) }
    return searchSyntax.filter {
        (!it.singleOrganization || organizations.size == 1) && (prefix == null || it.token.startsWith(prefix, true))
    }
}

internal fun insertSyntax(input: TextFieldValue, syntax: SearchSyntax): TextFieldValue {
    val completion = completionRange(input)
    val start = completion?.first ?: input.selection.min
    val end = completion?.let { it.last + 1 } ?: input.selection.max
    val leading = if (start > 0 && !input.text[start - 1].isWhitespace()) " " else ""
    val trailing = if (end == input.text.length || !input.text[end].isWhitespace()) " " else ""
    val inserted = leading + syntax.token + trailing
    val text = input.text.replaceRange(start, end, inserted)
    val valueStart = if (syntax.value.isEmpty()) -1 else syntax.token.lastIndexOf(syntax.value)
    val selection = if (valueStart < 0) TextRange(start + inserted.length)
        else TextRange(start + leading.length + valueStart, start + leading.length + valueStart + syntax.value.length)
    val shift = inserted.length - (end - start)
    val composition = input.composition?.let {
        when {
            it.max <= start -> it
            it.min >= end -> TextRange(it.start + shift, it.end + shift)
            else -> null // Explicitly replacing composing text commits that composition.
        }
    }
    return TextFieldValue(text, selection, composition)
}
