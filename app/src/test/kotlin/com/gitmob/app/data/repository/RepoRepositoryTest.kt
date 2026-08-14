package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.RepoList
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

class RepoRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val api = GHApiClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            tokenProvider = object : AccessTokenProvider {
                override suspend fun getToken() = "fake-token"
            },
            restBaseUrl = baseUrl,
            graphQLUrl = "$baseUrl/graphql",
        )
        repository = RepoRepository(api)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `解析仓库列表包含fork来源和topics`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "viewer": {
                      "repositories": {
                        "totalCount": 1,
                        "nodes": [
                          {
                            "name": "edgetunnel",
                            "owner": { "login": "cmliu", "avatarUrl": null },
                            "description": "desc",
                            "homepageUrl": null,
                            "isPrivate": false,
                            "isArchived": false,
                            "isFork": true,
                            "parent": { "name": "edgetunnel", "owner": { "login": "zizifn", "avatarUrl": null } },
                            "primaryLanguage": { "name": "JavaScript", "color": "#f1e05a" },
                            "stargazerCount": 40624,
                            "forkCount": 100,
                            "issues": { "totalCount": 2 },
                            "repositoryTopics": { "nodes": [ { "topic": { "name": "cloudflare" } } ] },
                            "defaultBranchRef": { "name": "main" }
                          }
                        ],
                        "pageInfo": { "hasNextPage": false, "endCursor": null }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = repository.getRepos() // login=null → viewer.repositories（和原来 getViewerRepos 行为一致）

        assertTrue(result is ApiResult.Success<RepoList>)
        val list = (result as ApiResult.Success<RepoList>).data
        assertEquals(1, list.totalCount)
        val item = list.items.first()
        assertEquals("edgetunnel", item.name)
        assertEquals("desc", item.description)
        assertEquals("cmliu", item.ownerLogin)
        assertTrue(item.isFork)
        assertEquals("zizifn", item.forkedFromOwner)
        assertEquals(listOf("cloudflare"), item.topics)
        assertEquals("main", item.defaultBranchName)
        assertEquals(40624, item.stargazerCount)
    }
}
