package com.gitmob.app.data.repository

import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.AppUpdate
import com.gitmob.app.data.model.AppUpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepository @Inject constructor(
    private val rest: GHApiClient,
    private val markdown: MarkdownRenderer,
    private val downloads: ExternalDownloadLauncher,
) {
    suspend fun check(installedVersionCode: Int): ApiResult<AppUpdateCheck> = safeCall {
        val release = try {
            rest.getPublic<UpdateReleaseDto>("/repos/xiaobaiweinuli/GitMob-Android/releases/latest")
        } catch (error: HttpStatusException) {
            if (error.code == 404) return@safeCall AppUpdateCheck.NoCompatibleRelease
            throw error
        }
        if (release.draft || release.prerelease) return@safeCall AppUpdateCheck.NoCompatibleRelease
        // Android versionCode 不在 Release API 中；只接受新应用约定的唯一通用 APK。
        val candidates = release.assets.mapNotNull { asset ->
            val match = APK_NAME.matchEntire(asset.name) ?: return@mapNotNull null
            val name = match.groupValues[1]
            val code = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val url = asset.downloadUrl.toHttpUrlOrNull()
            if (release.tag != "v$name" || code !in 1..2_100_000_000 ||
                asset.state != "uploaded" || asset.size <= 0 ||
                url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()
            ) return@mapNotNull null
            AppUpdate(name, code, release.name.orEmpty(), "", asset.downloadUrl, asset.size)
        }
        val update = candidates.singleOrNull() ?: return@safeCall AppUpdateCheck.NoCompatibleRelease
        if (update.versionCode <= installedVersionCode) return@safeCall AppUpdateCheck.UpToDate
        val html = withContext(Dispatchers.Default) { markdown.renderToHtml(release.body.orEmpty()) }
        AppUpdateCheck.Available(update.copy(notesHtml = html))
    }

    suspend fun download(update: AppUpdate): ApiResult<Unit> = safeCall { downloads.open(update.downloadUrl) }

    private companion object {
        val APK_NAME = Regex("GitMob-v([0-9]+[.][0-9]+[.][0-9]+)-([0-9]+)[.]apk")
    }
}

@Serializable
private data class UpdateReleaseDto(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<UpdateAssetDto>,
)

@Serializable
private data class UpdateAssetDto(
    val name: String,
    val state: String,
    val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
)
