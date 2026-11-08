package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.REST_API_VERSION
import com.gitmob.app.data.model.AppUpdateCheck
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppUpdateRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: AppUpdateRepository
    private val downloads = mockk<ExternalDownloadLauncher>(relaxed = true)
    private val markdown = object : MarkdownRenderer {
        override fun renderToHtml(markdown: String) = "<p>$markdown</p>"
    }

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repository = AppUpdateRepository(GHApiClient(OkHttpClient(), Json { ignoreUnknownKeys = true },
            object : AccessTokenProvider {
                override suspend fun getToken(): String? = error("Public update check must never read a token")
            }, server.url("/").toString().removeSuffix("/")), markdown, downloads)
    }
    @After fun teardown() { server.shutdown() }

    @Test fun publicCheckNeedsNoAccountAndUsesTheAgreedAsset() = runTest {
        enqueueRelease()
        val update = (success() as AppUpdateCheck.Available).update
        assertEquals(2, update.versionCode)
        assertEquals("0.2.0", update.versionName)
        assertEquals("fix(auth/nav): 修复登录 401", update.title)
        assertEquals("<p>Release notes</p>", update.notesHtml)
        val request = server.takeRequest()
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/releases/latest", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals(REST_API_VERSION, request.getHeader("X-GitHub-Api-Version"))
        assertEquals("GitMob-Android", request.getHeader("User-Agent"))
        assertEquals(ApiResult.Success(Unit), repository.download(update))
        verify(exactly = 1) { downloads.open("https://github.com/example/releases/download/v0.2.0/GitMob-v0.2.0-2.apk") }
    }

    @Test fun nullableReleaseTitleDoesNotDiscardBodyOrUpdate() = runTest {
        enqueueRelease(title = null)
        val update = (success() as AppUpdateCheck.Available).update
        assertEquals("", update.title)
        assertEquals("<p>Release notes</p>", update.notesHtml)
    }

    @Test fun numericCodeDeterminesWhetherUpdateIsNewer() = runTest {
        for (installed in listOf(2, 3, 100)) {
            enqueueRelease()
            assertEquals(AppUpdateCheck.UpToDate, success(installed))
        }
    }

    @Test fun legacyAndMalformedNamesAreNotCompatibleUpdates() = runTest {
        for (name in listOf("app-release.apk", "GitMob-v0.2.0.apk", "Other-v0.2.0-2.apk",
            "GitMob-v0.2.0-0.apk", "GitMob-v0.2.0-2147483648.apk", "GitMob-v0.2.0-2100000001.apk")) {
            enqueueRelease(assets = asset(name = name))
            assertEquals(name, AppUpdateCheck.NoCompatibleRelease, success())
        }
    }

    @Test fun mismatchedTagDraftPrereleaseAndMissingAssetsAreNotOffered() = runTest {
        enqueueRelease(tag = "v0.3.0")
        assertEquals(AppUpdateCheck.NoCompatibleRelease, success())
        enqueueRelease(flags = """ "draft":true,"prerelease":false """)
        assertEquals(AppUpdateCheck.NoCompatibleRelease, success())
        enqueueRelease(flags = """ "draft":false,"prerelease":true """)
        assertEquals(AppUpdateCheck.NoCompatibleRelease, success())
        enqueueRelease(assets = "")
        assertEquals(AppUpdateCheck.NoCompatibleRelease, success())
    }

    @Test fun incompleteAmbiguousAndUnsafeDownloadsAreNotOffered() = runTest {
        for (assets in listOf(asset(state = "starter"), asset(size = 0),
            asset(url = "http://github.com/app.apk"), asset(url = "https://user:password@github.com/app.apk"),
            asset() + "," + asset(name = "GitMob-v0.2.0-3.apk"))) {
            enqueueRelease(assets = assets)
            assertEquals(AppUpdateCheck.NoCompatibleRelease, success())
        }
    }

    @Test fun unrelatedReleaseAssetsDoNotHideTheCompatibleApk() = runTest {
        enqueueRelease(assets = asset(name = "mapping.txt") + "," + asset())
        assertTrue(success() is AppUpdateCheck.Available)
    }

    @Test fun missingReleaseHasNoCompatiblePackage() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(AppUpdateCheck.NoCompatibleRelease, success())
    }

    @Test fun rateLimitAndServerFailureRemainErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"))
        assertEquals(ApiResult.Failure(ApiError.RateLimited), repository.check(1))
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(repository.check(1) is ApiResult.Failure)
    }

    private suspend fun success(installed: Int = 1) = (repository.check(installed) as ApiResult.Success).data
    private fun enqueueRelease(
        tag: String = "v0.2.0", assets: String = asset(),
        title: String? = "fix(auth/nav): 修复登录 401",
        flags: String = """ "draft":false,"prerelease":false """,
    ) { server.enqueue(MockResponse().setBody("""{"tag_name":"$tag","name":${Json.encodeToString(title)},"body":"Release notes",$flags,"assets":[$assets]}""")) }
    private fun asset(
        name: String = "GitMob-v0.2.0-2.apk", state: String = "uploaded", size: Long = 1234,
        url: String = "https://github.com/example/releases/download/v0.2.0/GitMob-v0.2.0-2.apk",
    ) = """{"name":"$name","state":"$state","size":$size,"browser_download_url":"$url"}"""
}
