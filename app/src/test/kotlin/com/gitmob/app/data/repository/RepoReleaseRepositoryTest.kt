package com.gitmob.app.data.repository

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.gitmob.app.data.release.ReleaseAssetFiles
import com.gitmob.app.core.error.ApiError
import kotlinx.coroutines.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.SocketPolicy
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.testutil.TestAccessTokenProvider
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoReleaseAsset
import com.gitmob.app.data.model.SaveRepoReleaseInput
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepoReleaseRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val resolver = mockk<ContentResolver>()
    private val uri = Uri.parse("content://files/7")
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoReleaseRepository
    private lateinit var downloadLauncher: ExternalDownloadLauncher

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        downloadLauncher = mockk()
        every { downloadLauncher.open(any()) } returns Unit
        repository = RepoReleaseRepository(
            GHApiClient(
                okHttpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                tokenProvider = object : TestAccessTokenProvider {
                    override suspend fun getToken() = "fake-token"
                },
                restBaseUrl = baseUrl,
                ),
            ApolloClient.Builder()
                .serverUrl("$baseUrl/graphql")
                .okHttpClient(OkHttpClient())
                .addHttpInterceptor(
                    AuthHttpInterceptor(
                        object : TestAccessTokenProvider {
                            override suspend fun getToken() = "fake-token"
                        },
                    ),
                )
                .build(),
            downloadLauncher,
            ReleaseAssetFiles(resolver, folder.root),
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `repository permission comes from GraphQL viewerPermission`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"viewerPermission":"ADMIN"}}}"""))

        val result = repository.getRepositoryPermission("octo", "repo")

        assertEquals(RepoPermission.ADMIN, (result as ApiResult.Success).data)
        assertEquals("/graphql", server.takeRequest().path)
    }

    @Test
    fun `create release sends draft and prerelease state`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"node_id":"R7","tag_name":"v1.0.0","target_commitish":"main","name":"Version 1","body":"Notes","draft":true,"prerelease":false,"created_at":"2026-01-01T00:00:00Z","assets":[]}""",
            ),
        )

        val result = repository.createRelease(
            "octo",
            "repo",
            SaveRepoReleaseInput("v1.0.0", "main", "Version 1", "Notes", draft = true, prerelease = false),
        )

        val release = (result as ApiResult.Success).data
        assertTrue(release.draft)
        assertEquals("v1.0.0", release.tagName)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/releases", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"draft\":true"))
        assertTrue(body.contains("\"prerelease\":false"))
    }

    @Test
    fun `delete release accepts empty success response`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.deleteRelease("octo", "repo", releaseId = 7)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/repos/octo/repo/releases/7", request.path)
    }

    @Test
    fun `release list tag and id retain the actual web URL including draft URL`() = runTest(accounts.context) {
        val url = "https://github.com/octo/repo/releases/tag/untagged-draft"
        val body = """{"id":7,"tag_name":"feature/slash","draft":true,"html_url":"$url"}"""
        server.enqueue(MockResponse().setBody("[$body]"))
        server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setBody(body))

        assertEquals(url, (repository.getReleases("octo", "repo") as ApiResult.Success).data.items.single().htmlUrl)
        assertEquals(url, (repository.getReleaseByTag("octo", "repo", "feature/slash") as ApiResult.Success).data.htmlUrl)
        assertEquals(url, (repository.getRelease("octo", "repo", 7) as ApiResult.Success).data.htmlUrl)
        assertEquals("/repos/octo/repo/releases?per_page=${com.gitmob.app.core.network.PageSize.RELEASES}&page=1", server.takeRequest().path)
        assertEquals("/repos/octo/repo/releases/tags/feature%2Fslash", server.takeRequest().path)
        assertEquals("/repos/octo/repo/releases/7", server.takeRequest().path)
    }

    @Test
    fun `release asset download resolves redirect and opens external browser`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://objects.example/gitmob.apk"))
        val asset = RepoReleaseAsset(8, "gitmob.apk", null, "application/vnd.android.package-archive", "uploaded", 2048, 3, "", "")

        val result = repository.downloadAsset("octo", "repo", asset)

        assertTrue(result is ApiResult.Success)
        verify(exactly = 1) { downloadLauncher.open("https://objects.example/gitmob.apk") }
    }

    private fun attachment(bytes: ByteArray = byteArrayOf(0, 1, 2, 127, -1)) {
        every { resolver.query(uri, any(), null, null, null) } answers { MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("release asset+#.zip")) } }
        every { resolver.getType(uri) } returns "application/zip"
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
    }

    @Test fun uploadUsesReturnedUrlRawBinaryLengthAndExistingAuthentication() = runTest(accounts.context) {
        attachment()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":8,"name":"asset.zip","content_type":"application/zip","size":5,"state":"uploaded"}"""))
        val progress = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
        val result = repository.uploadAsset(server.url("/uploads/release/7").toString() + "{?name,label}", uri) { sent, total -> progress += sent to total }
        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/uploads/release/7?name=release%20asset%2B%23.zip", request.path)
        assertEquals("Bearer fake-token", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals(com.gitmob.app.core.network.REST_API_VERSION, request.getHeader("X-GitHub-Api-Version"))
        assertEquals("application/zip", request.getHeader("Content-Type"))
        assertEquals("5", request.getHeader("Content-Length"))
        org.junit.Assert.assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), request.body.readByteArray())
        assertEquals(0L to 5L, progress.first())
        assertEquals(5L to 5L, progress.last())
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun failedUploadKeepsErrorMappingAndCleansFile() = runTest(accounts.context) {
        attachment()
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        val result = repository.uploadAsset(server.url("/uploads/7").toString(), uri) { _, _ -> }
        assertEquals(ApiError.Forbidden, (result as ApiResult.Failure).error)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationClosesNetworkRequestAndCleansPreparedFile() = runTest(accounts.context) {
        attachment(ByteArray(256 * 1024))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = launch { repository.uploadAsset(server.url("/uploads/7").toString(), uri) { _, _ -> } }
        val request = withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) }
        org.junit.Assert.assertNotNull(request)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun accountSwitchCancelsAnEpochOwnedUploadAndDeletesTheCopy() = runTest(accounts.context) {
        attachment(ByteArray(128 * 1024))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val parent = Job(accounts.session.epoch.job)
        val task = CoroutineScope(coroutineContext + parent).launch { repository.uploadAsset(server.url("/uploads/7").toString(), uri) { _, _ -> } }
        org.junit.Assert.assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
        accounts.session.replace(emptyList(), null)
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
