package com.gitmob.app.data.repository

import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoReleaseRepository @Inject constructor(
    private val api: GHApiClient,
    private val downloadLauncher: ExternalDownloadLauncher,
) {
    suspend fun getRepositoryPermission(owner: String, name: String): ApiResult<RepoPermission> = safeCall {
        val data = api.graphQL<ReleasePermissionData>(
            "query ReleaseRepositoryPermission(\u0024owner: String!, \u0024name: String!) { repository(owner: \u0024owner, name: \u0024name) { viewerPermission } }",
            mapOf("owner" to JsonPrimitive(owner), "name" to JsonPrimitive(name)),
        )
        data.repository?.viewerPermission?.let { runCatching { RepoPermission.valueOf(it) }.getOrNull() } ?: RepoPermission.NONE
    }

    suspend fun getReleases(owner: String, name: String, page: Int = 1): ApiResult<RepoReleasePage> = safeCall {
        val values = api.get<List<RestRelease>>("/repos/$owner/$name/releases?per_page=30&page=$page", "application/vnd.github.full+json")
        RepoReleasePage(values.map(::toRelease), page, values.size >= 30)
    }
    suspend fun getRelease(owner: String, name: String, releaseId: Long): ApiResult<RepoRelease> = safeCall { toRelease(api.get("/repos/$owner/$name/releases/$releaseId", "application/vnd.github.full+json")) }
    suspend fun getReleaseByTag(owner: String, name: String, tag: String): ApiResult<RepoRelease> = safeCall { toRelease(api.get("/repos/$owner/$name/releases/tags/${encode(tag)}", "application/vnd.github.full+json")) }
    suspend fun createRelease(owner: String, name: String, input: SaveRepoReleaseInput): ApiResult<RepoRelease> = safeCall { toRelease(api.post("/repos/$owner/$name/releases", input.toRequest())) }
    suspend fun updateRelease(owner: String, name: String, releaseId: Long, input: SaveRepoReleaseInput): ApiResult<RepoRelease> = safeCall { toRelease(api.patch("/repos/$owner/$name/releases/$releaseId", input.toRequest())) }
    suspend fun deleteRelease(owner: String, name: String, releaseId: Long): ApiResult<Unit> = safeCall { api.delete<Unit>("/repos/$owner/$name/releases/$releaseId") }
    suspend fun generateNotes(owner: String, name: String, tag: String, target: String?, previousTag: String?): ApiResult<ReleaseNotes> = safeCall { val response = api.post<RestReleaseNotes, GenerateNotesRequest>("/repos/$owner/$name/releases/generate-notes", GenerateNotesRequest(tag, target, previousTag)); ReleaseNotes(response.name, response.body) }
    suspend fun uploadAsset(uploadUrl: String, fileName: String, label: String?, contentType: String, bytes: ByteArray): ApiResult<RepoReleaseAsset> = safeCall {
        val query = buildString { append(uploadUrl.substringBefore('{')); append("?name="); append(encode(fileName)); label?.takeIf { it.isNotBlank() }?.let { append("&label="); append(encode(it)) } }
        toAsset(api.uploadBytes<RestAsset>(query, contentType, bytes))
    }
    suspend fun updateAsset(owner: String, name: String, assetId: Long, assetName: String, label: String?): ApiResult<RepoReleaseAsset> = safeCall { toAsset(api.patch("/repos/$owner/$name/releases/assets/$assetId", UpdateAssetRequest(assetName, label))) }
    suspend fun deleteAsset(owner: String, name: String, assetId: Long): ApiResult<Unit> = safeCall { api.delete<Unit>("/repos/$owner/$name/releases/assets/$assetId") }
    suspend fun downloadAsset(owner: String, name: String, asset: RepoReleaseAsset): ApiResult<Unit> = safeCall {
        val url = api.resolveRestDownloadUrl("/repos/$owner/$name/releases/assets/${asset.id}", "application/octet-stream")
            ?: throw UserVisibleException(R.string.download_address_unavailable)
        downloadLauncher.open(url)
    }

    private fun SaveRepoReleaseInput.toRequest() = SaveReleaseRequest(tagName, targetCommitish, name, body, draft, prerelease)
    private fun toRelease(value: RestRelease) = RepoRelease(value.id, value.nodeId, value.tagName, value.targetCommitish, value.name, value.body.orEmpty(), value.bodyHtml, value.draft, value.prerelease, value.createdAt, value.publishedAt, value.author?.let { SimpleUser(it.login, it.name, it.avatarUrl, null) }, value.uploadUrl, value.assets.map(::toAsset), value.tarballUrl, value.zipballUrl)
    private fun toAsset(value: RestAsset) = RepoReleaseAsset(value.id, value.name, value.label, value.contentType, value.state, value.size, value.downloadCount, value.createdAt, value.updatedAt)
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
}

@Serializable private data class SaveReleaseRequest(@SerialName("tag_name") val tagName: String, @SerialName("target_commitish") val targetCommitish: String, val name: String?, val body: String, val draft: Boolean, val prerelease: Boolean)
@Serializable private data class GenerateNotesRequest(@SerialName("tag_name") val tagName: String, @SerialName("target_commitish") val targetCommitish: String? = null, @SerialName("previous_tag_name") val previousTagName: String? = null)
@Serializable private data class UpdateAssetRequest(val name: String, val label: String?)
@Serializable private data class RestReleaseNotes(val name: String? = null, val body: String = "")
@Serializable private data class ReleaseRestActor(val login: String = "", @SerialName("avatar_url") val avatarUrl: String? = null, val name: String? = null)
@Serializable private data class RestAsset(val id: Long, val name: String, val label: String? = null, @SerialName("content_type") val contentType: String = "application/octet-stream", val state: String = "uploaded", val size: Long = 0, @SerialName("download_count") val downloadCount: Int = 0, @SerialName("created_at") val createdAt: String = "", @SerialName("updated_at") val updatedAt: String = "")
@Serializable private data class RestRelease(val id: Long, @SerialName("node_id") val nodeId: String = "", @SerialName("tag_name") val tagName: String, @SerialName("target_commitish") val targetCommitish: String = "", val name: String? = null, val body: String? = null, @SerialName("body_html") val bodyHtml: String? = null, val draft: Boolean = false, val prerelease: Boolean = false, @SerialName("created_at") val createdAt: String = "", @SerialName("published_at") val publishedAt: String? = null, val author: ReleaseRestActor? = null, @SerialName("upload_url") val uploadUrl: String = "", val assets: List<RestAsset> = emptyList(), @SerialName("tarball_url") val tarballUrl: String? = null, @SerialName("zipball_url") val zipballUrl: String? = null)
@Serializable private data class ReleasePermissionData(val repository: ReleasePermissionRepository? = null)
@Serializable private data class ReleasePermissionRepository(val viewerPermission: String? = null)
