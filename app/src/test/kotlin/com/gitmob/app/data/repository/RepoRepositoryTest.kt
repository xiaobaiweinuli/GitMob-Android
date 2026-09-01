package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepositoryCreateInput
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
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

    @Test
    fun `创建个人仓库使用user repos并显式发送false默认值`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"name":"new-repo","owner":{"login":"octocat"}}
                """.trimIndent(),
            ),
        )

        val result = repository.createRepository(
            RepositoryCreateInput(
                owner = RepositoryCreateOwner("u1", "octocat", "Octocat", null, RepositoryCreateOwnerType.USER, true),
                name = "new-repo",
                description = null,
                isPrivate = false,
                addReadme = false,
                licenseTemplate = null,
                gitignoreTemplate = null,
            ),
        )

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/user/repos", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"private\":false"))
        assertTrue(body.contains("\"auto_init\":false"))
        assertTrue(!body.contains("license_template"))
    }

    @Test
    fun `创建组织仓库使用组织repos并映射owner`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"name":"org-repo","owner":{"login":"acme"}}
                """.trimIndent(),
            ),
        )

        val result = repository.createRepository(
            RepositoryCreateInput(
                owner = RepositoryCreateOwner("o1", "acme", null, null, RepositoryCreateOwnerType.ORGANIZATION, true),
                name = "org-repo",
                description = "description",
                isPrivate = true,
                addReadme = true,
                licenseTemplate = "mit",
                gitignoreTemplate = "Kotlin",
            ),
        )

        assertEquals("acme", (result as ApiResult.Success).data.owner)
        val request = server.takeRequest()
        assertEquals("/orgs/acme/repos", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"private\":true"))
        assertTrue(body.contains("\"auto_init\":true"))
        assertTrue(body.contains("\"license_template\":\"mit\""))
        assertTrue(body.contains("\"gitignore_template\":\"Kotlin\""))
    }

    @Test
    fun `license和gitignore模板路径正确映射`() = runTest {
        server.enqueue(MockResponse().setBody("[{\"key\":\"mit\",\"name\":\"MIT License\"}]"))
        server.enqueue(MockResponse().setBody("[\"Kotlin\",\"Android\"]"))

        val licenses = repository.getLicenseTemplates()
        val gitignore = repository.getGitignoreTemplates()

        assertEquals("mit", (licenses as ApiResult.Success).data.single().key)
        assertEquals(listOf("Kotlin", "Android"), (gitignore as ApiResult.Success).data)
        assertEquals("/licenses", server.takeRequest().path)
        assertEquals("/gitignore/templates", server.takeRequest().path)
    }

    @Test
    fun `创建仓库所有者查询使用PageSize并传递下一页cursor`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"id":"u1","login":"octocat","name":"Octocat","avatarUrl":null,"organizations":{"nodes":[{"id":"o1","login":"acme","name":"Acme","avatarUrl":null,"viewerCanCreateRepositories":true}],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-1"}}}}}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"id":"u1","login":"octocat","name":"Octocat","avatarUrl":null,"organizations":{"nodes":[{"id":"o2","login":"beta","name":"Beta","avatarUrl":null,"viewerCanCreateRepositories":false}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
                """.trimIndent(),
            ),
        )

        val first = repository.getRepositoryCreateOwners()
        val second = repository.getRepositoryCreateOwners("cursor-1")

        assertEquals("cursor-1", (first as ApiResult.Success).data.endCursor)
        assertEquals("beta", (second as ApiResult.Success).data.organizations.single().login)
        val firstBody = server.takeRequest().body.readUtf8()
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue(firstBody.contains("organizations(first: 20"))
        assertTrue(!firstBody.contains("\"after\":\"cursor-1\""))
        assertTrue(secondBody.contains("\"after\":\"cursor-1\""))
    }

    @Test
    fun `组织仓库列表返回真实创建权限上下文`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"repositoryOwner":{"__typename":"Organization","id":"o1","login":"acme","name":"Acme","avatarUrl":null,"viewerCanCreateRepositories":true,"repositories":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
                """.trimIndent(),
            ),
        )

        val result = repository.getRepos(login = "acme")

        val owner = (result as ApiResult.Success).data.ownerContext?.owner
        assertEquals("acme", owner?.login)
        assertEquals(RepositoryCreateOwnerType.ORGANIZATION, owner?.type)
        assertTrue(owner?.canCreateRepository == true)
    }
}
