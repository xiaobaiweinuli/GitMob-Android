package com.gitmob.app.data.repository

import com.gitmob.app.data.model.SearchCategory
import com.gitmob.app.data.model.SearchFileTarget
import java.net.URI

/** Only scope tokens outside quoted strings are interpreted. All other syntax stays with GitHub. */
internal fun unquotedSearchTokens(query: String): List<String> {
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    var quoted = false
    var containsQuote = false
    var escaped = false
    fun finish() {
        if (token.isNotEmpty() && !containsQuote) tokens += token.toString()
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
        SearchCategory.USERS -> setOf("type:user") to setOf("type:org")
        SearchCategory.ORGANIZATIONS -> setOf("type:org") to setOf("type:user")
        else -> return query
    }
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
    return SearchFileTarget(ref, ref.matches(Regex("[0-9a-fA-F]{40}")))
}
