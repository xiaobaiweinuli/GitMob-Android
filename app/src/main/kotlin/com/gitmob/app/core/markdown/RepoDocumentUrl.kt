package com.gitmob.app.core.markdown

import com.gitmob.app.data.model.RepoDocumentContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An intercepted origin, never a remote server or a container for credentials. */
internal const val REPO_DOCUMENT_HOST = "repo-docs.gitmob.invalid"

internal fun RepoDocumentContext.baseUrl(): HttpUrl =
    "https://$REPO_DOCUMENT_HOST/".toHttpUrl().newBuilder()
        .addPathSegments(filePath).build()

internal sealed interface RepoDocumentLink {
    data class File(val document: RepoDocumentContext) : RepoDocumentLink
    data class External(val url: String) : RepoDocumentLink
    data object Anchor : RepoDocumentLink
    data object Blocked : RepoDocumentLink
}

internal fun RepoDocumentContext.resolveLink(href: String): RepoDocumentLink {
    val url = baseUrl().resolve(href) ?: return RepoDocumentLink.Blocked
    if (url.host != REPO_DOCUMENT_HOST) return RepoDocumentLink.External(url.toString())
    val path = repositoryPath(url) ?: return RepoDocumentLink.Blocked
    if (path == filePath && url.fragment != null) return RepoDocumentLink.Anchor
    return RepoDocumentLink.File(copy(filePath = path))
}

/** Resolves resources emitted with the app's internal document origin. */
internal fun RepoDocumentContext.resourcePath(url: String): String? =
    baseUrl().resolve(url)?.let(::repositoryPath)?.takeIf { it.isNotBlank() && !it.endsWith('/') }

/**
 * Resolves a GitHub raw URL only when it identifies this exact repository and ref.
 * Matching the complete ref prevents a branch such as "main-other" from being
 * mistaken for "main". A ref containing '/' is matched by its path segments.
 */
internal fun RepoDocumentContext.rawResourcePath(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.host != "raw.githubusercontent.com" || parsed.port != 443 ||
        parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    val refSegments = ref.split('/').takeIf { it.isNotEmpty() && it.none(String::isEmpty) } ?: return null
    val segments = parsed.pathSegments
    if (segments.size < 4) return null
    if (!segments[0].equals(owner, ignoreCase = true) || !segments[1].equals(name, ignoreCase = true)) return null
    val rawRefs = if (ref.startsWith("refs/")) listOf(refSegments)
        else listOf(listOf("refs", "heads") + refSegments, refSegments)
    val matchedRef = rawRefs.firstOrNull { candidate ->
        segments.size > 2 + candidate.size && segments.subList(2, 2 + candidate.size) == candidate
    } ?: return null
    val path = segments.drop(2 + matchedRef.size).joinToString("/")
    return path.takeIf {
        it.isNotBlank() && !it.endsWith('/') && it.none { char -> char < ' ' } &&
            it.split('/').none { part -> part == "." || part == ".." }
    }
}

/** Resolves internal document URLs and matching GitHub raw URLs to one repository path. */
internal fun RepoDocumentContext.imageResourcePath(url: String): String? =
    resourcePath(url) ?: rawResourcePath(url)

private fun repositoryPath(url: HttpUrl): String? {
    if (url.scheme != "https" || url.host != REPO_DOCUMENT_HOST || url.port != 443 ||
        url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    // HttpUrl resolves dot segments and decodes each path segment exactly once.
    val path = url.pathSegments.joinToString("/")
    if (path.any { it < ' ' } || path.split('/').any { it == "." || it == ".." }) return null
    return path
}
