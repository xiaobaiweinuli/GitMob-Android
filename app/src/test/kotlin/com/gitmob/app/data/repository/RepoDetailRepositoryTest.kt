package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoForkSort
import com.gitmob.app.data.model.RepoForkSortField
import com.gitmob.app.data.model.RepoForkSortDirection
import com.gitmob.app.data.model.RepoDocumentContext
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.gitmob.app.testutil.TestAccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryFork
import com.gitmob.app.data.model.RepoDetail
import io.mockk.mockk
import io.mockk.every
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule

class RepoDetailRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoDetailRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val tokenProvider = object : TestAccessTokenProvider {
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
        server.close()
    }

    @Test
    fun `getRepoDetail解析权限并派生RepoCapabilities`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse(body =
                """
                {"data":{"viewer":{"id":"U1","login":"xiaobaiweinuli"},"repository":{
                    "viewerForks":{"nodes":[]},"id":"R1","name":"GitMob-Android","description":"desc","homepageUrl":null,
                    "url":"https://github.com/xiaobaiweinuli/GitMob-Android","visibility":"PUBLIC",
                    "owner":{"__typename":"User","id":"U1","login":"xiaobaiweinuli","avatarUrl":"https://avatars.example.com/u1","isViewer":true},
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
    fun `stargazers are mapped with cursor pagination`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body =
            """{"data":{"repository":{"stargazerCount":2,"stargazers":{"nodes":[{"login":"octocat","name":"Octo Cat","avatarUrl":"https://avatars.example.com/octocat","bio":"Hi"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}""",
        ))

        val result = repository.getStargazers("octo", "repo", "previous") as ApiResult.Success

        assertEquals(2, result.data.totalCount)
        val request = requireNotNull(server.takeRequest().body).utf8()
        assertTrue(request.contains("\"after\":\"previous\""))

        assertEquals("octocat", result.data.users.single().login)
        assertEquals("next", result.data.endCursor)
        assertTrue(result.data.hasNextPage)
    }

    @Test
    fun `empty stargazers with a positive independent count report unavailable`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body =
            """{"data":{"repository":{"stargazerCount":12,"stargazers":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
        ))
        val result = repository.getStargazers("octo", "repo") as ApiResult.Failure
        assertEquals(ApiError.UserVisible(R.string.repo_stargazers_unavailable), result.error)
    }

    @Test
    fun `zero stargazers is a valid empty list`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body =
            """{"data":{"repository":{"stargazerCount":0,"stargazers":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
        ))
        val result = repository.getStargazers("octo", "repo") as ApiResult.Success
        assertEquals(0, result.data.totalCount)
        assertTrue(result.data.users.isEmpty())
        assertFalse(result.data.hasNextPage)
    }

    @Test
    fun `empty final stargazer page is allowed after a cursor`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body =
            """{"data":{"repository":{"stargazerCount":12,"stargazers":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
        ))
        val result = repository.getStargazers("octo", "repo", "last") as ApiResult.Success
        assertTrue(result.data.users.isEmpty())
        assertFalse(result.data.hasNextPage)
    }

    @Test
    fun `stargazer permission failures propagate without a fallback request`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body =
            """{"data":{"repository":null},"errors":[{"type":"FORBIDDEN","path":["repository","stargazers"],"message":"Resource not accessible by personal access token"}]}""",
        ))
        val result = repository.getStargazers("octo", "repo") as ApiResult.Failure
        val error = result.error as ApiError.GraphQLError
        assertEquals("FORBIDDEN", error.errors.single().type)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `direct forks are mapped as repository cards`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body =
            """{"data":{"repository":{"forks":{"totalCount":1,"nodes":[{"name":"repo","owner":{"__typename":"User","login":"forker","avatarUrl":"https://example.com/forker.png"},"description":null,"homepageUrl":null,"visibility":"INTERNAL","isArchived":false,"isFork":true,"hasIssuesEnabled":true,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z","pushedAt":null,"parent":{"name":"repo","owner":{"__typename":"User","login":"octo","avatarUrl":"https://example.com/octo.png"}},"primaryLanguage":null,"stargazerCount":0,"forkCount":0,"issues":{"totalCount":0},"repositoryTopics":{"nodes":[]},"defaultBranchRef":{"name":"main"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
        ))

        val result = repository.getForks("octo", "repo") as ApiResult.Success

        assertEquals("forker", result.data.items.single().ownerLogin)
        assertEquals("octo", result.data.items.single().forkedFromOwner)
        assertEquals(RepoVisibility.INTERNAL, result.data.items.single().visibility)
        assertTrue(result.data.items.single().hasIssuesEnabled)
    }

    @Test
    fun `fork ordering is sent to GraphQL for every field and direction on every page`() = runTest(accounts.context) {
        val fields = mapOf(
            RepoForkSortField.CREATED to "CREATED_AT",
            RepoForkSortField.STARS to "STARGAZERS",
            RepoForkSortField.NAME to "NAME",
            RepoForkSortField.UPDATED to "UPDATED_AT",
            RepoForkSortField.PUSHED to "PUSHED_AT",
        )
        for ((field, apiField) in fields) {
            for (direction in RepoForkSortDirection.entries) {
                for (cursor in listOf(null, "next")) {
                    server.enqueue(MockResponse(body =
                        """{"data":{"repository":{"forks":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
                    ))
                    val result = repository.getForks("octo", "repo", cursor, RepoForkSort(field, direction))
                    assertTrue(result is ApiResult.Success)
                    val request = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()) as JsonObject
                    val variables = request["variables"] as JsonObject
                    assertEquals(JsonPrimitive("octo"), variables["owner"])
                    assertEquals(JsonPrimitive("repo"), variables["name"])
                    assertEquals(JsonPrimitive(PageSize.REPOS), variables["first"])
                    if (cursor == null) assertFalse(variables.containsKey("after"))
                    else assertEquals(JsonPrimitive(cursor), variables["after"])
                    assertEquals(buildJsonObject {
                        put("field", apiField)
                        put("direction", direction.name)
                    }, variables["orderBy"])
                    val query = (request["query"] as JsonPrimitive).content
                    assertTrue(query.contains("orderBy: \$orderBy"))
                }
            }
        }
    }

    private fun forkSource() = mockk<RepoDetail> {
        every { id } returns "SOURCE"
        every { ownerId } returns "UPSTREAM"
        every { ownerLogin } returns "octo"
        every { name } returns "repo"
    }
    private val forkTarget = RepositoryCreateOwner("VIEWER", "viewer", null, null, RepositoryCreateOwnerType.USER, true)
    private fun forkRest(id: Long = 2, node: String = "FORK", repoName: String = "renamed", owner: String = "viewer", ownerId: String = "VIEWER", fork: Boolean = true, root: Long = 1) =
        """{"id":$id,"node_id":"$node","full_name":"$owner/$repoName","name":"$repoName","owner":{"node_id":"$ownerId","login":"$owner"},"fork":$fork,"source":{"id":$root,"node_id":"SOURCE"},"description":"old","permissions":{"admin":true}}"""
    private fun enqueueSource() { server.enqueue(MockResponse(body = forkRest(1, "SOURCE", "repo", "octo", "UPSTREAM", false))) }
    private fun forkNode(id: String = "FORK", name: String = "renamed", parent: String = "SOURCE", parentFork: Boolean = false) =
        """{"id":"$id","name":"$name","owner":{"__typename":"User","id":"VIEWER","login":"viewer"},"description":"old","viewerPermission":"ADMIN","isArchived":false,"isFork":true,"parent":{"id":"$parent","isFork":$parentFork}}"""
    private fun forkPage(nodes: String = "", named: String = "null", next: Boolean = false, cursor: String = "null", count: Int = if (nodes.isEmpty()) 0 else 1, errors: String = "") =
        """{"data":{"repositoryOwner":{"__typename":"User","id":"VIEWER","login":"viewer","isViewer":true,"named":$named,"repositories":{"nodes":[$nodes],"totalCount":$count,"pageInfo":{"hasNextPage":$next,"endCursor":$cursor}}}}$errors}"""

    @Test fun personalForkOmitsOrganizationAndPreservesAcceptedIdentity() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 202, body = forkRest()))
        val result = repository.createFork(forkSource(), forkTarget, "renamed") as ApiResult.Success
        assertEquals("FORK", result.data.id)
        assertEquals("viewer/renamed", result.data.fullName)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/forks", request.target)
        assertEquals("""{"name":"renamed"}""", requireNotNull(request.body).utf8())
    }

    @Test fun organizationForkIncludesOrganizationButNeverDescription() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 202, body = forkRest()))
        repository.createFork(forkSource(), forkTarget.copy(type = RepositoryCreateOwnerType.ORGANIZATION), "renamed")
        assertEquals("""{"organization":"viewer","name":"renamed"}""", requireNotNull(server.takeRequest().body).utf8())
    }

    @Test fun renamedDirectForkIsDetectedAfterPagination() = runTest(accounts.context) {
        enqueueSource()
        server.enqueue(MockResponse(body = forkPage(forkNode("OTHER", "other", "OTHER_ROOT"), next = true, cursor = "\"next\"", count = 2)))
        server.enqueue(MockResponse(body = forkPage(forkNode(), count = 2)))
        val result = repository.checkForkTarget(forkSource(), forkTarget, "repo") as ApiResult.Success
        assertEquals("viewer/renamed", result.data.existing?.fullName)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        val firstRequest = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()) as JsonObject
        val nextRequest = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()) as JsonObject
        assertEquals(JsonPrimitive(PageSize.REPOS), (firstRequest["variables"] as JsonObject)["first"])
        assertEquals(JsonPrimitive("next"), (nextRequest["variables"] as JsonObject)["after"])
    }

    @Test fun organizationWithoutCreatePermissionCanReadExistingFork() = runTest(accounts.context) {
        val target = forkTarget.copy(type = RepositoryCreateOwnerType.ORGANIZATION, canCreateRepository = false)
        enqueueSource()
        server.enqueue(MockResponse(body =
            """{"data":{"repositoryOwner":{"__typename":"Organization","id":"VIEWER","login":"viewer","viewerCanCreateRepositories":false,"named":null,"repositories":{"nodes":[{"id":"FORK","name":"renamed","owner":{"__typename":"Organization","id":"VIEWER","login":"viewer"},"isFork":true,"parent":{"id":"SOURCE","isFork":false}}],"totalCount":1,"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
        ))

        val result = repository.checkForkTarget(forkSource(), target, "repo") as ApiResult.Success

        assertEquals("viewer/renamed", result.data.existing?.fullName)
        assertFalse(result.data.nameConflict)
    }

    @Test fun sameNetworkWithDifferentParentIsDetectedByRestSourceId() = runTest(accounts.context) {
        enqueueSource()
        server.enqueue(MockResponse(body = forkPage(forkNode(parent = "INTERMEDIATE", parentFork = true))))
        server.enqueue(MockResponse(body = forkRest()))
        val result = repository.checkForkTarget(forkSource(), forkTarget, "repo") as ApiResult.Success
        assertEquals("FORK", result.data.existing?.id)
    }

    @Test fun completeEmptyScanDistinguishesNameConflict() = runTest(accounts.context) {
        enqueueSource()
        server.enqueue(MockResponse(body = forkPage(named = forkNode(parent = "OTHER_ROOT"))))
        val result = repository.checkForkTarget(forkSource(), forkTarget, "repo") as ApiResult.Success
        assertTrue(result.data.nameConflict)
        assertEquals(null, result.data.existing)
    }

    @Test fun incompleteScanCannotBeReportedAsAvailable() = runTest(accounts.context) {
        enqueueSource()
        server.enqueue(MockResponse(body = forkPage(next = true)))
        assertTrue(repository.checkForkTarget(forkSource(), forkTarget, "repo") is ApiResult.Failure)
    }

    @Test fun permissionErrorOnNamedRepositoryCannotBecomeAnEmptyResult() = runTest(accounts.context) {
        enqueueSource()
        server.enqueue(MockResponse(body = forkPage(errors = ""","errors":[{"message":"denied","type":"FORBIDDEN","path":["repositoryOwner","named"]}]""")))
        assertTrue(repository.checkForkTarget(forkSource(), forkTarget, "repo") is ApiResult.Failure)
    }

    @Test fun expectedMissingNameStillAllowsCompleteForkScan() = runTest(accounts.context) {
        enqueueSource()
        server.enqueue(MockResponse(body = forkPage(errors = ""","errors":[{"message":"missing","type":"NOT_FOUND","path":["repositoryOwner","named"]}]""")))
        val result = repository.checkForkTarget(forkSource(), forkTarget, "repo") as ApiResult.Success
        assertEquals(null, result.data.existing)
        assertFalse(result.data.nameConflict)
    }

    private fun forkIdentity() =
        """{"data":{"node":{"__typename":"Repository","id":"FORK","name":"renamed","owner":{"__typename":"User","id":"VIEWER","login":"viewer"}}}}"""

    @Test fun acceptedForkCanRemainUnreadableThenBeConfirmed() = runTest(accounts.context) {
        val accepted = RepositoryFork("FORK", "VIEWER", "viewer", "renamed")
        server.enqueue(MockResponse(code = 404))
        assertEquals(null, (repository.confirmFork(forkSource(), forkTarget, accepted) as ApiResult.Success).data)
        server.enqueue(MockResponse(body = forkRest()))
        enqueueSource()
        server.enqueue(MockResponse(body = forkIdentity()))
        val confirmed = (repository.confirmFork(forkSource(), forkTarget, accepted) as ApiResult.Success).data
        assertEquals("FORK", confirmed?.id)
    }

    @Test fun replacedRepositoryIdentityCannotBeConfirmed() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = forkRest(node = "REPLACED")))
        enqueueSource()
        assertTrue(repository.confirmFork(forkSource(), forkTarget, RepositoryFork("FORK", "VIEWER", "viewer", "renamed")) is ApiResult.Failure)
    }

    @Test
    fun `deleteBranch成功时返回Unit`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = """{"data":{"deleteRef":{"clientMutationId":null}}}"""))

        val result = repository.deleteBranch("REF_123", "xiaobaiweinuli", "GitMob-Android")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `setDefaultBranch走REST并正确解析完整仓库响应`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse(body =
                """{"name":"GitMob-Android","default_branch":"claude"}""",
            ),
        )

        val result = repository.setDefaultBranch("xiaobaiweinuli", "GitMob-Android", "claude")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `createBranch读取来源提交并创建完整heads引用`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse(body =
                """{"ref":"refs/heads/feature/source","object":{"sha":"abc123"}}""",
            ),
        )
        server.enqueue(
            MockResponse(code = 201, body =
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
            sourceRequest.url.encodedPath,
        )
        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/refs", createRequest.target)
        val createBody = requireNotNull(createRequest.body).utf8()
        assertTrue(createBody.contains("\"ref\":\"refs/heads/feature/new\""))
        assertTrue(createBody.contains("\"sha\":\"abc123\""))
    }

    @Test
    fun `createEmptyBranch使用REST创建空tree根提交和引用`() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 201, body = """{"sha":"tree123"}"""))
        server.enqueue(MockResponse(code = 201, body = """{"sha":"commit123"}"""))
        server.enqueue(MockResponse(code = 201, body = """{"ref":"refs/heads/empty"}"""))

        val result = repository.createBranch(
            owner = "xiaobaiweinuli",
            name = "GitMob-Android",
            newBranchName = "empty",
            spec = com.gitmob.app.data.model.BranchCreationSpec.Empty,
        )

        assertTrue(result is ApiResult.Success)
        val treeRequest = server.takeRequest()
        assertEquals("POST", treeRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/trees", treeRequest.target)
        assertEquals("{\"tree\":[]}", requireNotNull(treeRequest.body).utf8())

        val commitRequest = server.takeRequest()
        assertEquals("POST", commitRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/commits", commitRequest.target)
        assertEquals(
            "{\"message\":\"Create empty branch\",\"tree\":\"tree123\",\"parents\":[]}",
            requireNotNull(commitRequest.body).utf8(),
        )

        val refRequest = server.takeRequest()
        assertEquals("POST", refRequest.method)
        assertEquals("/repos/xiaobaiweinuli/GitMob-Android/git/refs", refRequest.target)
        assertEquals(
            "{\"ref\":\"refs/heads/empty\",\"sha\":\"commit123\"}",
            requireNotNull(refRequest.body).utf8(),
        )
    }

    @Test
    fun `renameBranch使用REST并编码含斜杠的旧分支名`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse(code = 201, body =
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
            request.url.encodedPath,
        )
        assertEquals("{\"new_name\":\"feature/renamed\"}", requireNotNull(request.body).utf8())
    }

    @Test
    fun `transfer preflight accepts only missing repository errors with valid account data`() = runTest(accounts.context) {
        listOf("\"type\":\"NOT_FOUND\"", "\"extensions\":{\"type\":\"NOT_FOUND\"}").forEach { type ->
            server.enqueue(MockResponse(body = transferTargetResponse(
                errors = """[{"message":"Could not resolve to a Repository with the name 'Worker-TG-Bot/gpt_academic'.","path":["repositoryOwner","repository"],$type}]""",
            )))

            val result = repository.getTransferTarget("Worker-TG-Bot", "gpt_academic")

            assertTrue(result.toString(), result is ApiResult.Success)
            val target = (result as ApiResult.Success).data
            assertEquals("Worker-TG-Bot", target.account.login)
            assertTrue(target.account.canCreateRepository)
            assertFalse(target.hasNameConflict)
            val request = requireNotNull(server.takeRequest().body).utf8()
            assertTrue(request.contains("followRenames:false") || request.contains("followRenames: false"))
        }
    }

    @Test
    fun `transfer preflight keeps visible name conflicts`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = transferTargetResponse(repository = """{"id":"R2"}""")))

        val result = repository.getTransferTarget("Worker-TG-Bot", "gpt_academic")

        assertTrue((result as ApiResult.Success).data.hasNameConflict)
    }

    @Test
    fun `transfer preflight rejects other paths types and mixed errors`() = runTest(accounts.context) {
        val expected = """{"message":"Missing repository","path":["repositoryOwner","repository"],"type":"NOT_FOUND"}"""
        listOf(
            """[{"message":"Missing owner","path":["repositoryOwner"],"type":"NOT_FOUND"}]""",
            """[{"message":"Denied","path":["repositoryOwner","repository"],"type":"FORBIDDEN"}]""",
            """[{"message":"Unknown error","path":["repositoryOwner","repository"]}]""",
            """[$expected,{"message":"Rate limited","type":"RATE_LIMITED"}]""",
        ).forEach { errors ->
            server.enqueue(MockResponse(body = transferTargetResponse(errors = errors)))

            val result = repository.getTransferTarget("Worker-TG-Bot", "gpt_academic")

            assertTrue(result is ApiResult.Failure)
            val error = (result as ApiResult.Failure).error as ApiError.GraphQLError
            assertEquals(if (errors.contains("RATE_LIMITED")) 2 else 1, error.errors.size)
            if (errors.contains("FORBIDDEN")) assertEquals("FORBIDDEN", error.errors.single().type)
        }
    }

    @Test
    fun `transfer preflight never accepts missing account or transport failure`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body =
            """{"data":{"repositoryOwner":null},"errors":[{"message":"Missing","path":["repositoryOwner","repository"],"type":"NOT_FOUND"}]}""",
        ))
        assertTrue(repository.getTransferTarget("missing", "repo") is ApiResult.Failure)

        server.enqueue(MockResponse(code = 403, body = transferTargetResponse()))
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getTransferTarget("Worker-TG-Bot", "repo"))
    }

    private fun transferTargetResponse(repository: String = "null", errors: String = "[]") = """
        {"data":{"repositoryOwner":{"__typename":"Organization","id":"O1","login":"Worker-TG-Bot",
        "avatarUrl":"https://example.test/avatar","name":"Worker","viewerCanCreateRepositories":true,
        "repository":$repository}},"errors":$errors}
    """.trimIndent()

    @Test
    fun `transfer accepts 202 without treating response as the completed address`() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 202, body = """{"name":"repo"}"""))

        val result = repository.transferRepository("octo", "repo", "target", "renamed")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/transfer", request.target)
        assertEquals("{\"new_owner\":\"target\",\"new_name\":\"renamed\"}", requireNotNull(request.body).utf8())
    }

    @Test
    fun `delete repository accepts empty 204 response`() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 204))

        val result = repository.deleteRepository("octo", "repo")

        assertTrue(result is ApiResult.Success)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `visibility forbidden response remains a permission error`() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 403, body = """{"message":"Forbidden"}"""))

        val result = repository.setVisibility("octo", "repo", "private")

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Forbidden, (result as ApiResult.Failure).error)
    }

    @Test
    fun `topics mutation returns invalid names for partial success handling`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = """{"data":{"updateTopics":{"invalidTopicNames":["bad topic"]}}}"""))

        val result = repository.updateTopics("R1", listOf("android", "bad-topic"))

        assertEquals(listOf("bad topic"), (result as ApiResult.Success).data)
    }

    @Test
    fun `settings mutation with empty payload fails instead of reporting success`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = """{"data":{"updateRepository":null}}"""))

        val result = repository.updateSettings("R1", description = "updated")

        assertTrue(result is ApiResult.Failure)
    }
    private fun readmeBody(markdown: String, path: String = "docs/README.md"): JsonObject = buildJsonObject {
        put("type", "file")
        put("path", path)
        put("encoding", "base64")
        put("size", markdown.toByteArray().size)
        put("sha", "blob-sha-must-not-be-used-as-ref")
        put("content", Base64.getMimeEncoder(24, byteArrayOf(13, 10)).encodeToString(markdown.toByteArray()))
    }

    @Test fun preferredReadmeUsesRestPathAndDecodesMultilineBase64WithoutChangingMarkdown() = runTest(accounts.context) {
        val markdown = "# 中文 README\n\n    indented code\n![图](images/a.png)\n[中文](README_CN.md)\n"
        server.enqueue(MockResponse(body = readmeBody(markdown).toString()))
        val ref = "feature/docs +中文"
        val result = repository.getReadme("tiann", "KernelSU", ref) as ApiResult.Success
        val readme = requireNotNull(result.data)
        assertEquals(markdown, readme.markdown)
        assertEquals(RepoDocumentContext("tiann", "KernelSU", ref, "docs/README.md"), readme.context)
        assertFalse(readme.isTruncated)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/tiann/KernelSU/readme", request.url.encodedPath)
        assertEquals(ref, request.url.queryParameter("ref"))
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals(1, server.requestCount)
    }

    @Test fun preferredReadmeRetainsGithubSelectedDirectoryAndMarkdownExtension() = runTest(accounts.context) {
        for (path in listOf("README.md", ".github/README.md", "docs/ReadMe.MARKDOWN", "README")) {
            server.enqueue(MockResponse(body = readmeBody("# Hello", path).toString()))
            val result = repository.getReadme("o", "r", "tag/v1") as ApiResult.Success
            assertEquals(path, result.data!!.context.filePath)
            server.takeRequest()
        }
    }

    @Test fun missingOrEmptyReadmeIsAbsentButRequestErrorsAreNot() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 404, body = "{}"))
        assertEquals(ApiResult.Success(null), repository.getReadme("o", "r", "main"))
        for (text in listOf("", " \n\t")) {
            server.enqueue(MockResponse(body = readmeBody(text).toString()))
            assertEquals(ApiResult.Success(null), repository.getReadme("o", "r", "main"))
        }
        for (code in listOf(401, 403, 429, 500)) {
            server.enqueue(MockResponse(code = code, body = "{}"))
            assertTrue(repository.getReadme("o", "r", "main") is ApiResult.Failure)
        }
    }

    @Test fun incompleteOrInvalidReadmeCannotBeMistakenForAbsentOrCompleteContent() = runTest(accounts.context) {
        val valid = readmeBody("# Hello")
        val invalid = listOf(
            JsonObject(valid - "content"),
            JsonObject(valid - "size"),
            JsonObject(valid + ("size" to JsonPrimitive(9000))),
            JsonObject(valid + ("encoding" to JsonPrimitive("none"))),
            JsonObject(valid + ("content" to JsonPrimitive("%%%%"))),
            JsonObject(valid + mapOf("content" to JsonPrimitive("/w=="), "size" to JsonPrimitive(1))),
            JsonObject(valid + ("path" to JsonPrimitive("../README.md"))),
        )
        for (response in invalid) {
            server.enqueue(MockResponse(body = response.toString()))
            assertEquals(response.toString(), ApiResult.Failure(ApiError.UserVisible(R.string.repo_readme_invalid)),
                repository.getReadme("o", "r", "main"))
        }
    }

    @Test fun otherMarkupFormatsDoNotRenderAsMarkdown() = runTest(accounts.context) {
        for (path in listOf("README.rst", "README.adoc", "README.org", "README.html")) {
            server.enqueue(MockResponse(body = readmeBody("content", path).toString()))
            assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.repo_readme_unsupported)),
                repository.getReadme("o", "r", "main"))
        }
    }

    @Test fun binaryReadmeIsNotPassedToTheMarkdownRenderer() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = readmeBody("hello\u0000binary").toString()))
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.repo_readme_unsupported)),
            repository.getReadme("o", "r", "main"))
    }

}
