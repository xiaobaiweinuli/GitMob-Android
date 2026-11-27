package com.gitmob.app.data.repository

import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.RepoContentDownloadDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun contentApiPath(owner: String, name: String, ref: String, path: String): String {
    require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == "." || it == ".." })
    val url = "https://api.github.com/".toHttpUrl().newBuilder()
        .addPathSegment("repos").addPathSegment(owner).addPathSegment(name)
        .addPathSegment("contents").addPathSegments(path).addQueryParameter("ref", ref).build()
    return "${url.encodedPath}?${url.encodedQuery}"
}

internal suspend fun GHApiClient.getContentDownload(apiPath: String): RepoContentDownloadDto {
    val content = get<RepoContentDownloadDto>(apiPath, "application/vnd.github.object+json")
    val downloadUrl = content.downloadUrl?.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty()
    }?.toString()
    return content.copy(downloadUrl = downloadUrl)
}
