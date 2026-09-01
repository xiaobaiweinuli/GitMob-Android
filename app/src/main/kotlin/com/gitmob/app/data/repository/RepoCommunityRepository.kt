package com.gitmob.app.data.repository

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.RepoContributor
import com.gitmob.app.data.model.RepoLicenseDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoCommunityRepository @Inject constructor(private val api: GHApiClient) {
    suspend fun getContributors(owner: String, name: String, page: Int = 1): ApiResult<Pair<List<RepoContributor>, Boolean>> = safeCall {
        val values = api.get<List<RestContributor>>("/repos/$owner/$name/contributors?per_page=${PageSize.CONTRIBUTORS}&page=$page&anon=1")
        values.map { RepoContributor(it.login ?: it.name, it.avatarUrl, it.htmlUrl, it.contributions, it.type) } to
            (values.size >= PageSize.CONTRIBUTORS)
    }
    suspend fun getLicense(owner: String, name: String, ref: String): ApiResult<RepoLicenseDocument> = safeCall {
        val value = api.get<RestLicense>("/repos/$owner/$name/license?ref=$ref")
        val decoded = if (value.encoding.equals("base64", true)) Base64.getMimeDecoder().decode(value.content.orEmpty()).decodeToString() else value.content.orEmpty()
        RepoLicenseDocument(value.license?.name ?: value.name, value.license?.spdxId, value.path, value.htmlUrl, decoded)
    }
}

@Serializable private data class RestContributor(val login: String? = null, val name: String? = null, @SerialName("avatar_url") val avatarUrl: String? = null, @SerialName("html_url") val htmlUrl: String? = null, val contributions: Int = 0, val type: String? = null)
@Serializable private data class RestLicenseInfo(val name: String = "", @SerialName("spdx_id") val spdxId: String? = null)
@Serializable private data class RestLicense(val name: String = "LICENSE", val path: String = "LICENSE", @SerialName("html_url") val htmlUrl: String? = null, val content: String? = null, val encoding: String? = null, val license: RestLicenseInfo? = null)
