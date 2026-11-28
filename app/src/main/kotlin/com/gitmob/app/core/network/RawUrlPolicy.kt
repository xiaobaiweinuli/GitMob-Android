package com.gitmob.app.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Raw transport addresses only; model URLs and repository resource identities stay unchanged. */
@Singleton
class RawUrlPolicy @Inject constructor() {
    fun normalizePrefix(input: String): String? {
        val text = input.trim().let { if ("://" in it) it else "https://$it" }
        if (hasUserInfo(text) || text.any { it in "{}$" }) return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || url.host == RAW_HOST ||
            url.encodedPath.contains("https://", ignoreCase = true)) return null
        return url.toString().trimEnd('/') + "/"
    }

    fun accepts(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Boolean {
        if (hasUserInfo(url)) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host == RAW_HOST && parsed.port == 443 &&
            parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null &&
            method in setOf("GET", "HEAD") && headers.keys.none {
                it.equals("Authorization", true) || it.equals("Cookie", true) || it.equals("Proxy-Authorization", true)
            }
    }

    fun rewrite(url: String, prefix: String?, method: String = "GET", headers: Map<String, String> = emptyMap()): String {
        if (prefix == null || !accepts(url, method, headers)) return url
        val normalized = normalizePrefix(prefix) ?: return url
        return normalized + url
    }

    private fun hasUserInfo(url: String): Boolean =
        url.substringAfter("://", "").takeWhile { it !in "/\\?#" }.contains('@')

    private companion object { const val RAW_HOST = "raw.githubusercontent.com" }
}
