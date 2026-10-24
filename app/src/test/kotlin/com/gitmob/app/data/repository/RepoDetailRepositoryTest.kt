package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.RepoVisibility
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepoDetailRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoDetailRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val tokenProvider = object : AccessTokenProvider {
            override suspend fun getToken() = "fake-token"
        }
        val api = GHApiClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            tokenProvider = tokenProvider,
            restBaseUrl = baseUrl,
        )
        val apollo = ApolloClient.Builder()
            .serverUrl("$baseUrl/graphql")
            .okHttpClient(OkHttpClient())
            .addHttpInterceptor(AuthHttpInterceptor(tokenProvider))
            .build()
        repository = RepoDetailRepository(api, apollo)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `getRepoDetail解析权限并派生RepoCapabilities`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"repository":{
                    "id":"R1","name":"GitMob-Android","description":"desc","homepageUrl":null,
                    "url":"https://github.com/xiaobaiweinuli/GitMob-Android","visibility":"PUBLIC",
                    "owner":{"__typename":"User","login":"xiaobaiweinuli","avatarUrl":"https://avatars.example.com/u1","isViewer":true},
                    "isPrivate":false,"isArchived":false,"isTemplate":true,"isFork":false,"parent":null,
                    "stargazerCount":20,"viewerHasStarred":false,"forkCount":2,"forkingAllowed":true,
                    "issues":{"totalCount":2},"pullRequests":{"totalCount":0},
                    "watchers":{"totalCount":1},"viewerSubscription":"IGNORED","viewerCanSubscribe":true,
                    "licenseInfo":{"name":"Apache License 2.0","spdxId":"Apache-2.0"},
                    "refs":{"totalCount":6},"defaultBranchRef":{"name":"claude"},
                    "releases":{"totalCount":43,"nodes":[{"name":"GitMob v1.7.8","tagName":"v1.7.8"}]},
                    "primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                    "repositoryTopics":{"nodes":[{"topic":{"name":"github"}},{"topic":{"name":"android"}}]},
                    "viewerPermission":"ADMIN","viewerCanCreateIssues":true,"hasIssuesEnabled":true,
                    "isBlankIssuesEnabled":false,"issueCreationPolicy":"COLLABORATORS_ONLY",
                    "hasPullRequestsEnabled":true,"pullRequestCreationPolicy":"ALL",
                    "hasDiscussionsEnabled":true,"discussions":{"totalCount":3}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getRepoDetail("xiaobaiweinuli", "GitMob-Android")

        assertTrue(result is ApiResult.Success)
        val detail = (result as ApiResult.Success).data
        assertEquals("GitMob-Android", detail.name)
        assertEquals("desc", detail.description)
        assertEquals("claude", detail.defaultBranchName)
        assertEquals("v1.7.8", detail.latestReleaseTag)
        assertEquals(listOf("github", "android"), detail.topics)
        assertTrue(detail.capabilities.canDeleteRepo) // ADMIN 应该派生出可删除仓库
        assertTrue(detail.capabilities.canManageBranchProtection)
        assertTrue(detail.capabilities.canDeleteIssues)
        assertTrue(detail.viewerCanCreateIssues)
        assertTrue(detail.hasIssuesEnabled)
        assertTrue(detail.ownerIsViewer)
        assertTrue(detail.forkingAllowed)
    }

    @Test
    fun `stargazers are mapped with cursor pagination`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"data":{"repository":{"stargazers":{"totalCount":2,"nodes":[{"login":"octocat","name":"Octo Cat","avatarUrl":"https://avatars.example.com/octocat","bio":"Hi"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}""",
        ))

        val result = repository.getStargazers("octo", "repo") as ApiResult.Success

        assertEquals("octocat", result.data.users.single().login)
        assertEquals("next", result.data.endCursor)
        assertTrue(result.data.hasNextPage)
    }

    @Test
    fun `direct forks are mapped as repository cards`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"data":{"repository":{"forks":{"totalCount":1,"nodes":[{"name":"repo","owner":{"__typename":"User","login":"forker","avatarUrl":null},"description":null,"homepageUrl":null,"visibility":"INTERNAL","isArchived":false,"isFork":true,"hasIssuesEnabled":true,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z","pushedAt":null,"parent":{"name":"repo","owner":{"__typename":"User","login":"octo","avatarUrl":null}},"primaryLanguage":null,"stargazerCount":0,"forkCount":0,"issues":{"totalCount":0},"repositoryTopics":{"nodes":[]},"defaultBranchRef":{"name":"main"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
        ))

        val result = repository.getForks("octo", "repo") as ApiResult.Success

        assertEquals("forker", result.data.items.single().ownerLogin)
        assertEquals("octo", result.data.items.single().forkedFromOwner)
        assertEquals(RepoVisibility.INTERNAL, result.data.items.single().visibility)
        assertTrue(result.data.items.single().hasIssuesEnabled)
    }

    @Test
    fun `create fork accepts 202 with an empty JSON body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"name":"repo"}"""))

        val result = repository.createFork("octo", "repo")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/forks", request.path)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun `deleteBranch成功时返回Unit`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"deleteRef":{"clientMutationId":null}}}"""))

        val result = repository.deleteBranch("REF_123", "xiaobaiweinuli", "GitMob-Android")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `setDefaultBranch走REST并正确解析完整仓库响应`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"name":"GitMob-Android","default_branch":"claude"}""",
            ),
        )

        val result = repository.setDefaultBranch("xiaobaiweinuli", "GitMob-Android", "claude")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `createBranch读取来源提交并创建完整heads引用`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"ref":"refs/heads/feature/source","object":{"sha":"abc123"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"ref":"refs/heads/feature/new","object":{"sha":"abc123"}}""",
            ),
        )

        val result = repository.createBranch(
            owner = "xiaobaiweinuli",
            name = "GitMob-Android",
            newBranchName = "feature/new",
            spec = com.gitmob.app.data.model.BranchCreationSpec.FromExisting("feature/source"),
        )

        assertTrue(result is ApiResult.Success)
        val sourceRequest = server.takeRequest()
        assertEquals("GET", sourceRequest.method)
        assertEquals(
            "/repos/xiaobaiweinuli/GitMob-Android/git/ref/heads/feature%2Fsource",
            sourceRequest.requestUrl?.encodedPath,
        )
        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/refs", createRequest.path)
        val createBody = createRequest.body.readUtf8()
        assertTrue(createBody.contains("\"ref\":\"refs/heads/feature/new\""))
        assertTrue(createBody.contains("\"sha\":\"abc123\""))
    }

    @Test
    fun `createEmptyBranch使用REST创建空tree根提交和引用`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sha":"tree123"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sha":"commit123"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ref":"refs/heads/empty"}"""))

        val result = repository.createBranch(
            owner = "xiaobaiweinuli",
            name = "GitMob-Android",
            newBranchName = "empty",
            spec = com.gitmob.app.data.model.BranchCreationSpec.Empty,
        )

        assertTrue(result is ApiResult.Success)
        val treeRequest = server.takeRequest()
        assertEquals("POST", treeRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/trees", treeRequest.path)
        assertEquals("{\"tree\":[]}", treeRequest.body.readUtf8())

        val commitRequest = server.takeRequest()
        assertEquals("POST", commitRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/commits", commitRequest.path)
        assertEquals(
            "{\"message\":\"Create empty branch\",\"tree\":\"tree123\",\"parents\":[]}",
            commitRequest.body.readUtf8(),
        )

        val refRequest = server.takeRequest()
        assertEquals("POST", refRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/refs", refRequest.path)
        assertEquals(
            "{\"ref\":\"refs/heads/empty\",\"sha\":\"commit123\"}",
            refRequest.body.readUtf8(),
        )
    }

    @Test
    fun `renameBranch使用REST并编码含斜杠的旧分支名`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"name":"feature/renamed"}""",
            ),
        )

        val result = repository.renameBranch(
            owner = "xiaobaiweinuli",
            name = "GitMob-Android",
            branchName = "feature/old",
            newBranchName = "feature/renamed",
        )

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            "/repos/xiaobaiweinuli/GitMob-Android/branches/feature%2Fold/rename",
            request.requestUrl?.encodedPath,
        )
        assertEquals("{\"new_name\":\"feature/renamed\"}", request.body.readUtf8())
    }

    @Test
    fun `transfer preflight accepts only missing repository errors with valid account data`() = runTest {
        listOf("\"type\":\"NOT_FOUND\"", "\"extensions\":{\"type\":\"NOT_FOUND\"}").forEach { type ->
            server.enqueue(MockResponse().setBody(transferTargetResponse(
                errors = """[{"message":"Could not resolve to a Repository with the name 'Worker-TG-Bot/gpt_academic'.","path":["repositoryOwner","repository"],$type}]""",
            )))

            val result = repository.getTransferTarget("Worker-TG-Bot", "gpt_academic")

            assertTrue(result.toString(), result is ApiResult.Success)
            val target = (result as ApiResult.Success).data
            assertEquals("Worker-TG-Bot", target.account.login)
            assertTrue(target.account.canCreateRepository)
            assertFalse(target.hasNameConflict)
            val request = server.takeRequest().body.readUtf8()
            assertTrue(request.contains("followRenames:false") || request.contains("followRenames: false"))
        }
    }

    @Test
    fun `transfer preflight keeps visible name conflicts`() = runTest {
        server.enqueue(MockResponse().setBody(transferTargetResponse(repository = """{"id":"R2"}""")))

        val result = repository.getTransferTarget("Worker-TG-Bot", "gpt_academic")

        assertTrue((result as ApiResult.Success).data.hasNameConflict)
    }

    @Test
    fun `transfer preflight rejects other paths types and mixed errors`() = runTest {
        val expected = """{"message":"Missing repository","path":["repositoryOwner","repository"],"type":"NOT_FOUND"}"""
        listOf(
            """[{"message":"Missing owner","path":["repositoryOwner"],"type":"NOT_FOUND"}]""",
            """[{"message":"Denied","path":["repositoryOwner","repository"],"type":"FORBIDDEN"}]""",
            """[{"message":"Unknown error","path":["repositoryOwner","repository"]}]""",
            """[$expected,{"message":"Rate limited","type":"RATE_LIMITED"}]""",
        ).forEach { errors ->
            server.enqueue(MockResponse().setBody(transferTargetResponse(errors = errors)))

            val result = repository.getTransferTarget("Worker-TG-Bot", "gpt_academic")

            assertTrue(result is ApiResult.Failure)
            val error = (result as ApiResult.Failure).error as ApiError.GraphQLError
            assertEquals(if (errors.contains("RATE_LIMITED")) 2 else 1, error.errors.size)
            if (errors.contains("FORBIDDEN")) assertEquals("FORBIDDEN", error.errors.single().type)
        }
    }

    @Test
    fun `transfer preflight never accepts missing account or transport failure`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"data":{"repositoryOwner":null},"errors":[{"message":"Missing","path":["repositoryOwner","repository"],"type":"NOT_FOUND"}]}""",
        ))
        assertTrue(repository.getTransferTarget("missing", "repo") is ApiResult.Failure)

        server.enqueue(MockResponse().setResponseCode(403).setBody(transferTargetResponse()))
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getTransferTarget("Worker-TG-Bot", "repo"))
    }

    private fun transferTargetResponse(repository: String = "null", errors: String = "[]") = """
        {"data":{"repositoryOwner":{"__typename":"Organization","id":"O1","login":"Worker-TG-Bot",
        "avatarUrl":"https://example.test/avatar","name":"Worker","viewerCanCreateRepositories":true,
        "repository":$repository}},"errors":$errors}
    """.trimIndent()

    @Test
    fun `transfer accepts 202 without treating response as the completed address`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"name":"repo"}"""))

        val result = repository.transferRepository("octo", "repo", "target", "renamed")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/transfer", request.path)
        assertEquals("{\"new_owner\":\"target\",\"new_name\":\"renamed\"}", request.body.readUtf8())
    }

    @Test
    fun `delete repository accepts empty 204 response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.deleteRepository("octo", "repo")

        assertTrue(result is ApiResult.Success)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `visibility forbidden response remains a permission error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))

        val result = repository.setVisibility("octo", "repo", "private")

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Forbidden, (result as ApiResult.Failure).error)
    }

    @Test
    fun `topics mutation returns invalid names for partial success handling`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"updateTopics":{"invalidTopicNames":["bad topic"]}}}"""))

        val result = repository.updateTopics("R1", listOf("android", "bad-topic"))

        assertEquals(listOf("bad topic"), (result as ApiResult.Success).data)
    }

    @Test
    fun `settings mutation with empty payload fails instead of reporting success`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"updateRepository":null}}"""))

        val result = repository.updateSettings("R1", description = "updated")

        assertTrue(result is ApiResult.Failure)
    }
}
