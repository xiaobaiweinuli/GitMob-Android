package com.gitmob.app.data.repository

import com.gitmob.app.data.model.SearchCategory
import com.gitmob.app.data.model.SearchFileTarget
import java.net.URI

/** Only scope tokens outside quoted strings are interpreted. All other syntax stays with GitHub. */
internal fun unquotedSearchTokens(query: String, includeQuotedValues: Boolean = false): List<String> {
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    var quoted = false
    var containsQuote = false
    var escaped = false
    fun finish() {
        if (token.isNotEmpty() && (!containsQuote || (includeQuotedValues && token.first() != '"'))) tokens += token.toString()
        token.clear()
        containsQuote = false
    }
    for (char in query) {
        when {
            escaped -> { token.append(char); escaped = false }
            char == '\\' -> { token.append(char); escaped = true }
            char == '"' -> { quoted = !quoted; containsQuote = true; token.append(char) }
            char.isWhitespace() && !quoted -> finish()
            else -> token.append(char)
        }
    }
    finish()
    return tokens
}

internal fun scopedSearchQuery(query: String, category: SearchCategory): String? {
    val tokens = unquotedSearchTokens(query).map(String::lowercase)
    val (expected, opposite) = when (category) {
        SearchCategory.ISSUES -> setOf("is:issue", "type:issue") to setOf("is:pr", "type:pr")
        SearchCategory.PULL_REQUESTS -> setOf("is:pr", "type:pr") to setOf("is:issue", "type:issue")
        SearchCategory.DISCUSSIONS -> setOf("is:discussion") to setOf("is:issue", "type:issue", "is:pr", "type:pr")
        SearchCategory.USERS -> setOf("type:user") to setOf("type:org")
        SearchCategory.ORGANIZATIONS -> setOf("type:org") to setOf("type:user")
        else -> return query
    }
    // Advanced issue queries can contain OR/nested groups. Constrain the entire expression.
    if (category in setOf(SearchCategory.ISSUES, SearchCategory.PULL_REQUESTS) &&
        tokens.any { it == "or" || '(' in it || ')' in it }
    ) return "($query) "+expected.first()
    if (tokens.any { it in opposite || (it.startsWith('-') && it.drop(1) in expected) }) return null
    return if (tokens.any { it in expected }) query else "$query "+expected.first()
}

/** Remove the known file-path suffix; splitting after /blob/ at the first slash loses slash refs. */
internal fun searchFileTarget(htmlUrl: String, owner: String, repository: String, path: String): SearchFileTarget? {
    val uri = runCatching { URI(htmlUrl) }.getOrNull() ?: return null
    if (uri.scheme != "https" || !uri.host.equals("github.com", true)) return null
    val prefix = "/$owner/$repository/blob/"
    val suffix = "/$path"
    val urlPath = uri.path ?: return null
    if (!urlPath.startsWith(prefix) || !urlPath.endsWith(suffix)) return null
    val ref = urlPath.removePrefix(prefix).removeSuffix(suffix)
    if (ref.isBlank()) return null
    return SearchFileTarget(ref)
}

/** The visible input never carries the repository scope. GitHub interprets the remaining syntax. */
internal fun repositoryCodeSearchQuery(input: String, owner: String, name: String, includeForks: Boolean): String? {
    val query = input.trim()
    if (query.isEmpty()) return null
    val qualifiers = unquotedSearchTokens(query, includeQuotedValues = true)
    var hasRepo = false
    for (token in qualifiers) {
        if (':' !in token) continue
        when (token.substringBefore(':').lowercase()) {
            "repo" -> {
                if (!token.substringAfter(':').removeSurrounding("\"").equals("$owner/$name", true)) return null
                hasRepo = true
            }
            "-repo", "user", "-user", "org", "-org" -> return null
        }
    }
    return buildList {
        if (!hasRepo) add("repo:$owner/$name")
        add(query)
        if (includeForks && qualifiers.none { it.startsWith("fork:", ignoreCase = true) }) add("fork:true")
    }.joinToString(" ")
}

internal fun repositoryCodeSearchInput(path: String): String {
    if (path.isEmpty()) return ""
    val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
    return if (path.any { it.isWhitespace() || it == '"' || it == '\\' }) "path:\"$escaped\"" else "path:$path"
}
