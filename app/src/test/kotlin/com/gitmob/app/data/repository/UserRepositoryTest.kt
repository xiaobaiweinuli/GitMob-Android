package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.EditableProfile
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.model.ProfileDraft
import com.gitmob.app.data.model.ProfileOwner
import com.gitmob.app.data.model.ProfileSaveResult
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun buildApolloClient(baseUrl: String): ApolloClient =
    ApolloClient.Builder()
        .serverUrl("$baseUrl/graphql")
        .okHttpClient(OkHttpClient())
        .addHttpInterceptor(
            AuthHttpInterceptor(
                object : AccessTokenProvider {
                    override suspend fun getToken() = "fake-token"
                },
            ),
        )
        .build()

private fun buildUserRepository(baseUrl: String, session: AccountSession, storage: TokenStorage = mockk(relaxed = true)) = UserRepository(
    buildApolloClient(baseUrl),
    GHApiClient(OkHttpClient(), Json { ignoreUnknownKeys = true; explicitNulls = false },
        object : AccessTokenProvider { override suspend fun getToken() = "fake-token" }, baseUrl),
    storage,
    session,
)

/**
 * 用 MockWebServer 模拟真实 HTTP 响应，不 Mock 内部逻辑，纯 JVM 跑，
 * 仅作请求契约与边界的辅助回归，不能替代本轮 APK 的原生验收。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var repository: UserRepository
    private lateinit var storage: TokenStorage

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        storage = mockk(relaxed = true)
        repository = buildUserRepository(baseUrl, accounts.session, storage)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private val sampleNodeJson = """
        {
            "id":"U_kg1","login":"octocat","name":"The Octocat","avatarUrl":"https://x/a.png",
            "bio":"hello","company":"@github","location":"SF","websiteUrl":"https://x.dev",
            "email":"o@x.com","pronouns":"they/them","isDeveloperProgramMember":true,
            "isBountyHunter":false,"isCampusExpert":false,"isGitHubStar":false,
            "isViewer":true,"viewerCanFollow":false,"viewerIsFollowing":false,
            "status":null,
            "socialAccounts":{"nodes":[{"displayName":"@oct","provider":"TWITTER","url":"https://x.com/oct"}]},
            "followers":{"totalCount":10},
            "following":{"totalCount":3},
            "organizations":{"totalCount":2},
            "repositories":{"totalCount":5},
            "starredRepositories":{"totalCount":20},
            "gists":{"totalCount":4},
            "pinnedItems":{"nodes":[
                {"__typename":"Repository","name":"repo1","url":"https://github.com/octocat/repo1","description":"desc :rocket:",
                 "stargazerCount":100,"forkCount":4,"primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                 "owner":{"__typename":"User","login":"octocat","avatarUrl":"https://x/a.png"}}
            ]}
        }
    """.trimIndent()

    @Test
    fun `getViewerProfile 一次GraphQL请求拿全部数据`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{
                    "viewer":$sampleNodeJson,
                    "issuesInvolvingMe":{"issueCount":3},
                    "prsInvolvingMe":{"issueCount":1},
                    "discussionsInvolvingMe":{"discussionCount":2}
                }}
                """.trimIndent(),
            ),
        )

        val result = repository.getViewerProfile()

        assertTrue(result is ApiResult.Success)
        val profile = (result as ApiResult.Success).data
        assertEquals("octocat", profile.user.login)
        assertEquals("they/them", profile.extra?.pronouns)
        assertEquals(10, profile.user.followers)
        assertEquals(2, profile.user.organizationsCount)
        assertEquals(5, profile.repoCount)
        assertEquals(20, profile.starredCount)
        assertEquals(4, profile.gistCount)
        assertEquals(1, profile.pinnedRepos.size)
        assertEquals("repo1", profile.pinnedRepos.first().name)
        assertEquals("desc :rocket:", profile.pinnedRepos.first().description)
        assertTrue(profile.followState.isViewer)
        assertEquals(3, profile.involvedIssueCount)
        assertEquals(1, profile.involvedPrCount)
        assertEquals(2, profile.involvedDiscussionCount)
        assertEquals(1, server.requestCount)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
        assertEquals(result, repository.getViewerProfile())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `别人的资料 isViewer为false且展示关注状态`() = runTest(accounts.context) {
        val othersJson = sampleNodeJson
            .replace("\"isViewer\":true", "\"isViewer\":false")
            .replace("\"viewerCanFollow\":false", "\"viewerCanFollow\":true")
        server.enqueue(MockResponse().setBody("""{"data":{"user":$othersJson}}"""))

        val result = repository.getUserProfile("octocat")

        assertTrue(result is ApiResult.Success)
        val profile = (result as ApiResult.Success).data
        assertFalse(profile.followState.isViewer)
        assertTrue(profile.followState.viewerCanFollow)
        val variables = takeVariables()
        assertEquals(JsonPrimitive("octocat"), variables["login"])
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
    }

    @Test
    fun `GraphQL返回401时映射为Unauthorized`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))

        val result = repository.getViewerProfile()

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `独立用户仓库查询同步可见性日期议题开关及容量参数`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""
            {"data":{"user":{"repositories":{"totalCount":1,"nodes":[{
                "name":"repo","owner":{"__typename":"User","login":"octocat","avatarUrl":"https://x/a.png"},
                "description":null,"homepageUrl":null,"visibility":"PRIVATE","isArchived":false,"isFork":false,
                "hasIssuesEnabled":true,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z","pushedAt":null,
                "parent":null,"primaryLanguage":null,"stargazerCount":0,"forkCount":0,"issues":{"totalCount":0},
                "repositoryTopics":{"nodes":[]},"defaultBranchRef":null
            }],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
        """.trimIndent()))

        val result = repository.getUserRepos("octocat", "previous") as ApiResult.Success

        val repo = result.data.items.single()
        assertEquals(RepoVisibility.PRIVATE, repo.visibility)
        assertTrue(repo.hasIssuesEnabled)
        assertEquals(0, repo.openIssueCount)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), repo.updatedAt)
        assertEquals(null, repo.pushedAt)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.REPOS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        assertEquals(JsonPrimitive("previous"), variables["after"])
    }

    @Test
    fun `关注和组织分页请求统一容量并保留游标`() = runTest(accounts.context) {
        val loaders = listOf<Triple<String, String, suspend () -> ApiResult<*>>>(
            Triple("user", "followers", suspend { repository.getFollowers("octocat", "previous") }),
            Triple("user", "following", suspend { repository.getFollowing("octocat", "previous") }),
            Triple("user", "organizations", suspend { repository.getUserOrgs("octocat", "previous") }),
            Triple("organization", "membersWithRole", suspend { repository.getOrgMembers("github", "previous") }),
        )
        for ((root, field, load) in loaders) {
            server.enqueue(MockResponse().setBody("""{"data":{"$root":{"$field":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))

            assertTrue(load() is ApiResult.Success)
            val variables = takeVariables()
            assertEquals(JsonPrimitive(if (field == "organizations") PageSize.ORGS else PageSize.USER_LIST), variables["first"])
            assertEquals(JsonPrimitive("previous"), variables["after"])
            assertEquals(JsonPrimitive(if (root == "organization") "github" else "octocat"), variables[if (root == "organization") "orgLogin" else "login"])
        }
    }

    @Test
    fun `viewer和user组织预览保持固定容量而不伪造分页`() = runTest(accounts.context) {
        for (login in listOf(null, "octocat")) {
            val root = if (login == null) "viewer" else "user"
            server.enqueue(MockResponse().setBody("""{"data":{"$root":{"organizations":{"nodes":[]}}}}"""))

            assertTrue((repository.getOrganizations(login) as ApiResult.Success).data.isEmpty())
            val variables = takeVariables()
            assertEquals(JsonPrimitive(PageSize.ORGS_PREVIEW), variables["first"])
            assertEquals(login?.let(::JsonPrimitive), variables["login"])
            assertFalse("after" in variables)
        }
    }

    @Test
    fun `独立用户星标同时传入列表和话题容量`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"user":{"starredRepositories":{"totalCount":0,"edges":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))

        val page = (repository.getUserStars("octocat", "previous") as ApiResult.Success).data

        assertTrue(page.items.isEmpty())
        assertTrue(page.hasNextPage)
        assertEquals("next", page.endCursor)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.STARRED_REPOS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        assertEquals(JsonPrimitive("octocat"), variables["login"])
        assertEquals(JsonPrimitive("previous"), variables["after"])
    }

    @Test
    fun `缺失用户或组织根节点不伪造成空分页`() = runTest(accounts.context) {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"data":{"user":null}}""")) }
        assertTrue(repository.getUserProfile("missing") is ApiResult.Failure)
        assertTrue(repository.getUserStars("missing") is ApiResult.Failure)
        assertTrue(repository.getUserOrgs("missing") is ApiResult.Failure)
        server.enqueue(MockResponse().setBody("""{"data":{"organization":null}}"""))
        assertTrue(repository.getOrgMembers("missing") is ApiResult.Failure)
    }

    @Test
    fun `编辑资料GET一次拿八字段且null映射为空文本和关闭开关`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{
            "id":1,"node_id":"U_kg1","login":"octocat","name":null,"avatar_url":null,
            "email":null,"blog":null,"twitter_username":null,"company":null,"location":null,"hireable":null,"bio":null
        }"""))

        val profile = (repository.getEditableProfile() as ApiResult.Success).data

        assertEquals(ProfileDraft(), profile.values)
        assertEquals(AuthenticatedUser(1, "U_kg1", "octocat", null, null), profile.user)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/user", request.path)
        assertEquals("Bearer fake-token", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2026-03-10", request.getHeader("X-GitHub-Api-Version"))
        assertEquals(1, server.requestCount)
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
    }

    @Test
    fun `八字段PATCH原样发送并采用服务端值同步账号缓存和事件`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(viewerResponse()))
        val before = (repository.getViewerProfile() as ApiResult.Success).data
        server.takeRequest()
        val original = editable(ProfileDraft(name = "Old"))
        val draft = ProfileDraft("  Name  ", "line one\n😀 line two", "  Company  ", "  Location  ", "https://example.test/page?q=1", "me+tag@example.test", "Name_123", true)
        val savedValues = draft.copy(name = "Server name", email = "")
        val saved = editable(savedValues, login = "renamed")
        val events = mutableListOf<EditableProfile>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.profileUpdatesFor(accounts.session.epoch).collect { events += it }
        }
        server.enqueue(MockResponse().setBody(editableResponse(saved)))

        assertEquals(ApiResult.Success(ProfileSaveResult(saved)), repository.saveEditableProfile(original, draft))
        runCurrent()

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/user", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals(buildJsonObject {
            put("name", draft.name); put("bio", draft.bio); put("company", draft.company); put("location", draft.location)
            put("blog", draft.blog); put("email", draft.email); put("twitter_username", draft.twitterUsername); put("hireable", true)
        }, Json.parseToJsonElement(request.body.readUtf8()))
        assertEquals(listOf(saved), events)
        coVerify(exactly = 1) { storage.updateIdentity(saved.user) }
        val active = accounts.session.state.value.activeAccount!!
        assertEquals("1", active.key)
        assertEquals(saved.user.login, active.login)
        assertEquals(saved.user.name, active.name)
        assertEquals(saved.user.nodeId, active.nodeId)
        val cached = (repository.getViewerProfile() as ApiResult.Success).data
        assertEquals(savedValues.name, cached.user.name)
        assertEquals(savedValues.bio, cached.user.bio)
        assertEquals(savedValues.email, cached.user.email)
        assertEquals(savedValues.company, cached.user.company)
        assertEquals(savedValues.location, cached.user.location)
        assertEquals(savedValues.blog, cached.user.websiteUrl)
        assertEquals(before.extra, cached.extra)
        assertEquals(before.pinnedRepos, cached.pinnedRepos)
        assertEquals(before.repoCount, cached.repoCount)
        assertEquals(before.involvedIssueCount, cached.involvedIssueCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `清空明确发送空字符串false且仅Twitter发送null`() = runTest(accounts.context) {
        val original = editable(ProfileDraft("Name", "Bio", "Company", "Location", "https://example.test", "me@example.test", "Name_123", true))
        server.enqueue(MockResponse().setBody(editableResponse(editable(ProfileDraft()))))

        val result = repository.saveEditableProfile(original, ProfileDraft()) as ApiResult.Success

        assertEquals(ProfileDraft(), result.data.profile.values)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("name", "bio", "company", "location", "blog", "email", "twitter_username", "hireable"), body.keys)
        listOf("name", "bio", "company", "location", "blog", "email").forEach { assertEquals(JsonPrimitive(""), body[it]) }
        assertEquals(JsonNull, body["twitter_username"])
        assertEquals(JsonPrimitive(false), body["hireable"])
    }

    @Test
    fun `未修改不发请求且局部修改仅发送差异字段`() = runTest(accounts.context) {
        val original = editable(ProfileDraft(name = "Original"))
        assertEquals(ApiResult.Success(ProfileSaveResult(original)), repository.saveEditableProfile(original, original.values))
        assertEquals(0, server.requestCount)
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
        val changed = original.values.copy(name = "  Exact input  ")
        server.enqueue(MockResponse().setBody(editableResponse(editable(changed))))

        assertTrue(repository.saveEditableProfile(original, changed) is ApiResult.Success)

        assertEquals(JsonObject(mapOf("name" to JsonPrimitive(changed.name))), Json.parseToJsonElement(server.takeRequest().body.readUtf8()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `校验错误和原始身份不匹配在发送前拒绝`() = runTest(accounts.context) {
        val original = editable(ProfileDraft())
        for (invalid in listOf(
            ProfileDraft(bio = "😀".repeat(161)), ProfileDraft(email = "broken"),
            ProfileDraft(blog = " https://example.test"), ProfileDraft(twitterUsername = "@name"),
        )) {
            assertEquals(ApiResult.Failure(ApiError.UserVisible(com.gitmob.app.R.string.profile_validation_failed)), repository.saveEditableProfile(original, invalid))
        }
        assertTrue(repository.saveEditableProfile(original.copy(user = original.user.copy(id = 2)), ProfileDraft(name = "Changed")) is ApiResult.Failure)
        assertEquals(0, server.requestCount)
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
    }

    @Test
    fun `资料GET拒绝缺失或错误身份且不按相同login猜测`() = runTest(accounts.context) {
        val epoch = accounts.session.epoch
        val valid = editableResponse(editable(ProfileDraft(), login = "viewer"))
        for (invalid in listOf(
            "{}", valid.replace("\"id\":1", "\"id\":0"), valid.replace("\"id\":1", "\"id\":2"),
            valid.replace("\"node_id\":\"U_kg1\"", "\"node_id\":\"\""), valid.replace("\"login\":\"viewer\"", "\"login\":\"\""),
        )) {
            server.enqueue(MockResponse().setBody(invalid))
            assertTrue(repository.getEditableProfile() is ApiResult.Failure)
        }
        assertSame(epoch, accounts.session.epoch)
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
    }

    @Test
    fun `保存失败或返回另一身份不改元信息缓存或发保存事件`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(viewerResponse()))
        val before = repository.getViewerProfile()
        server.takeRequest()
        val epoch = accounts.session.epoch
        val state = accounts.session.state.value
        val events = mutableListOf<EditableProfile>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.profileUpdatesFor(epoch).collect { events += it } }
        val original = editable(ProfileDraft(name = "Old"))
        val changed = original.values.copy(name = "Changed")
        for (failure in listOf(
            MockResponse().setResponseCode(401), MockResponse().setResponseCode(403),
            MockResponse().setResponseCode(422).setBody("""{"message":"Validation Failed"}"""),
            MockResponse().setBody("{}"),
            MockResponse().setBody(editableResponse(editable(changed).let { it.copy(user = it.user.copy(id = 2)) })),
        )) {
            server.enqueue(failure)
            assertTrue(repository.saveEditableProfile(original, changed) is ApiResult.Failure)
            server.takeRequest()
            assertEquals(before, repository.getViewerProfile())
        }
        runCurrent()
        assertSame(epoch, accounts.session.epoch)
        assertEquals(state, accounts.session.state.value)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
    }

    @Test
    fun `远端保存成功本地持久化失败仍同步当前资料并单独返回标记`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(viewerResponse()))
        repository.getViewerProfile()
        server.takeRequest()
        val original = editable(ProfileDraft(name = "Old"))
        val saved = editable(ProfileDraft(name = "Saved"))
        coEvery { storage.updateIdentity(any()) } throws java.io.IOException("Storage unavailable")
        val events = mutableListOf<EditableProfile>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.profileUpdatesFor(accounts.session.epoch).collect { events += it } }
        server.enqueue(MockResponse().setBody(editableResponse(saved)))

        assertEquals(ApiResult.Success(ProfileSaveResult(saved, localSyncFailed = true)), repository.saveEditableProfile(original, saved.values))
        runCurrent()

        assertEquals(listOf(saved), events)
        assertEquals("Saved", accounts.session.state.value.activeAccount?.name)
        assertEquals("Saved", (repository.getViewerProfile() as ApiResult.Success).data.user.name)
        coVerify(exactly = 0) { storage.saveCredentials(any(), any()) }
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
    }

    @Test
    fun `保存期间迟到的主页和个人资料使用保存值且后续刷新可采用新值`() = runTest(accounts.context) {
        for (owner in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val original = editable(ProfileDraft(name = "Old"))
            val saved = editable(ProfileDraft(name = "Saved"))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/graphql") {
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return MockResponse().setBody(if (owner) ownerResponse() else viewerResponse())
                    }
                    return MockResponse().setBody(editableResponse(saved))
                }
            }
            val late = async(Dispatchers.IO) {
                if (owner) repository.getProfileOwner("octocat") else repository.getViewerProfileFresh()
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
                assertTrue(repository.saveEditableProfile(original, saved.values) is ApiResult.Success)
            } finally {
                release.countDown()
            }
            val result = (late.await() as ApiResult.Success).data
            if (owner) assertEquals("Saved", (result as ProfileOwner.Person).name)
            else assertEquals("Saved", (result as com.gitmob.app.data.model.ViewerProfile).user.name)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody((if (owner) ownerResponse() else viewerResponse()).replace("The Octocat", "New remote value"))
            }
            val fresh = if (owner) repository.getProfileOwner("octocat") else repository.getViewerProfileFresh()
            val value = (fresh as ApiResult.Success).data
            if (owner) assertEquals("New remote value", (value as ProfileOwner.Person).name)
            else assertEquals("New remote value", (value as com.gitmob.app.data.model.ViewerProfile).user.name)
        }
    }

    @Test
    fun `PATCH在途切换后迟到响应不写入新账号缓存或元信息`() = runTest {
        val old = accounts.session.epoch
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/user") {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return MockResponse().setBody(editableResponse(editable(ProfileDraft(name = "Late old"))))
                }
                return MockResponse().setBody(viewerResponse().replace("The Octocat", "Second account"))
            }
        }
        val result = async(Dispatchers.IO + AccountContext(old)) {
            try {
                repository.saveEditableProfile(editable(ProfileDraft()), ProfileDraft(name = "Late old"))
                false
            } catch (_: CancellationException) { true }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            val second = GitHubAccount("2", 2, "U_2", "second")
            accounts.session.replace(listOf(second), second.key)
        } finally {
            release.countDown()
        }
        assertTrue(result.await())
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
        assertEquals("second", accounts.session.state.value.activeAccount?.login)
        withContext(accounts.context) {
            assertEquals("Second account", (repository.getViewerProfile() as ApiResult.Success).data.user.name)
        }
    }

    private fun editable(values: ProfileDraft, login: String = "octocat") = EditableProfile(
        AuthenticatedUser(1, "U_kg1", login, values.name, "https://x/a.png"), values,
    )

    private fun editableResponse(profile: EditableProfile) = buildJsonObject {
        put("id", profile.user.id); put("node_id", profile.user.nodeId); put("login", profile.user.login)
        put("avatar_url", profile.user.avatarUrl?.let(::JsonPrimitive) ?: JsonNull)
        put("name", profile.values.name); put("bio", profile.values.bio); put("company", profile.values.company); put("location", profile.values.location)
        put("blog", profile.values.blog); put("email", profile.values.email); put("twitter_username", profile.values.twitterUsername); put("hireable", profile.values.hireable)
    }.toString()

    private fun viewerResponse() = """{"data":{"viewer":$sampleNodeJson,"issuesInvolvingMe":{"issueCount":3},"prsInvolvingMe":{"issueCount":1},"discussionsInvolvingMe":{"discussionCount":2}}}"""

    private fun ownerResponse(): String {
        val node = JsonObject(Json.parseToJsonElement(sampleNodeJson).jsonObject + mapOf(
            "__typename" to JsonPrimitive("User"), "url" to JsonPrimitive("https://github.com/octocat"),
        ))
        return """{"data":{"viewer":{"login":"octocat","gists":{"totalCount":4}},"repositoryOwner":$node}}"""
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
}

class UserRepositoryProfileOwnerTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = buildUserRepository(baseUrl, accounts.session)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `repositoryOwner返回User时分流为Person`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"login":"octocat","gists":{"totalCount":9}},"repositoryOwner":{
                    "__typename":"User","id":"U_kg1","login":"octocat","avatarUrl":"https://x/a.png","url":"https://github.com/octocat",
                    "name":"The Octocat","bio":"hi","company":"@github","location":"SF","email":"octo@example.com",
                    "pronouns":"he/him","status":{"emoji":"🌙","message":"Coding"},
                    "isDeveloperProgramMember":false,"isBountyHunter":false,
                    "isCampusExpert":false,"isGitHubStar":false,
                    "socialAccounts":{"nodes":[{"displayName":"@octocat","provider":"TWITTER","url":"https://x.com/octocat"}]},
                    "isViewer":false,
                    "viewerCanFollow":true,"viewerIsFollowing":false,
                    "followers":{"totalCount":10},"following":{"totalCount":2},
                    "organizations":{"totalCount":1},"starredRepositories":{"totalCount":8},
                    "gists":{"totalCount":6},
                    "websiteUrl":"https://x.dev","repositories":{"totalCount":5},
                    "pinnedItems":{"nodes":[{
                        "__typename":"Repository","name":"repo1","url":"https://github.com/octocat/repo1","description":"desc",
                        "stargazerCount":10,"forkCount":2,"primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                        "owner":{"__typename":"User","login":"octocat","avatarUrl":"https://x/a.png"}
                    }]}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getProfileOwner("octocat")

        assertTrue(result is ApiResult.Success)
        val owner = (result as ApiResult.Success).data
        assertTrue(owner is com.gitmob.app.data.model.ProfileOwner.Person)
        owner as com.gitmob.app.data.model.ProfileOwner.Person
        assertEquals("octocat", owner.login)
        assertTrue(owner.followState.viewerCanFollow)
        assertFalse(owner.isViewer)
        assertEquals("octo@example.com", owner.email)
        assertEquals("@github", owner.company)
        assertEquals("SF", owner.location)
        assertEquals("Coding", owner.status?.message)
        assertEquals(6, owner.gistCount)
        assertEquals(1, owner.socialAccounts.size)
        assertEquals("@octocat", owner.socialAccounts.first().displayName)
        assertEquals(1, owner.pinnedRepos.size)
        assertEquals("octocat", owner.pinnedRepos.first().ownerLogin)
        assertEquals("repo1", owner.pinnedRepos.first().name)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
        assertEquals(JsonPrimitive("octocat"), variables["login"])
    }

    @Test
    fun `repositoryOwner是viewer时Gist数量使用viewer的ALL统计`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{
                    "viewer":{"login":"octocat","gists":{"totalCount":9}},
                    "repositoryOwner":{
                        "__typename":"User","id":"U_kg1","login":"octocat",
                        "avatarUrl":"https://x/a.png","url":"https://github.com/octocat",
                        "name":null,"bio":null,"company":null,"location":null,"email":"",
                        "pronouns":null,"status":null,
                        "socialAccounts":{"nodes":[]},
                        "isDeveloperProgramMember":false,"isBountyHunter":false,
                        "isCampusExpert":false,"isGitHubStar":false,
                        "isViewer":true,"viewerCanFollow":false,"viewerIsFollowing":false,
                        "followers":{"totalCount":0},"following":{"totalCount":0},
                        "organizations":{"totalCount":0},"starredRepositories":{"totalCount":0},
                        "websiteUrl":null,"repositories":{"totalCount":0},
                        "gists":{"totalCount":6},
                        "pinnedItems":{"nodes":[]}
                    }
                }}
                """.trimIndent(),
            ),
        )

        val result = repository.getProfileOwner("octocat")

        val owner = (result as ApiResult.Success).data as com.gitmob.app.data.model.ProfileOwner.Person
        assertEquals(9, owner.gistCount)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
    }

    @Test
    fun `repositoryOwner返回Organization时分流为Org`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"login":"octocat","gists":{"totalCount":9}},"repositoryOwner":{
                    "__typename":"Organization","id":"O_kg1","login":"github","avatarUrl":"https://x/o.png","url":"https://github.com/github",
                    "name":"GitHub","description":"desc","isVerified":true,
                    "viewerCanCreateRepositories":true,"viewerIsFollowing":false,
                    "websiteUrl":"https://github.com","membersWithRole":{"totalCount":100},
                    "repositories":{"totalCount":300},
                    "pinnedItems":{"nodes":[{
                        "__typename":"Repository","name":"docs","url":"https://github.com/github/docs","description":"docs",
                        "stargazerCount":20,"forkCount":3,"primaryLanguage":null,
                        "owner":{"__typename":"Organization","login":"github","avatarUrl":"https://x/o.png"}
                    }]}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getProfileOwner("github")

        assertTrue(result is ApiResult.Success)
        val owner = (result as ApiResult.Success).data
        assertTrue(owner is com.gitmob.app.data.model.ProfileOwner.Org)
        owner as com.gitmob.app.data.model.ProfileOwner.Org
        assertTrue(owner.isVerified)
        assertEquals("desc", owner.description)
        assertEquals(100, owner.membersCount)
        assertEquals(300, owner.repoCount)
        assertEquals(1, owner.pinnedRepos.size)
        assertEquals("github", owner.pinnedRepos.first().ownerLogin)
        assertEquals("docs", owner.pinnedRepos.first().name)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
        assertEquals(JsonPrimitive("github"), variables["login"])
    }

    @Test
    fun `统一资料查询缺失owner时仍返回失败`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"login":"octocat","gists":{"totalCount":0}},"repositoryOwner":null}}"""))
        assertTrue(repository.getProfileOwner("missing") is ApiResult.Failure)
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
    private fun orgReadmeBody(
        text: String? = "# Organization\n![Logo](images/logo.png)",
        visibility: String = "PUBLIC",
        binary: Boolean = false,
        truncated: Boolean = false,
    ): String = """
        {"data":{"repository":{"visibility":"$visibility","defaultBranchRef":{
          "target":{"__typename":"Commit","oid":"commit-123","file":{
            "path":"profile/README.md","object":{"__typename":"Blob",
              "text":${if (text == null) "null" else JsonPrimitive(text)},"isBinary":$binary,"isTruncated":$truncated}
          }}
        }}}}
    """.trimIndent()

    @Test fun organizationReadmeUsesDefaultBranchCommitAndProfilePath() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(orgReadmeBody()))
        val result = repository.getOrgProfileReadme("github") as ApiResult.Success
        val readme = requireNotNull(result.data)
        assertEquals("# Organization\n![Logo](images/logo.png)", readme.markdown)
        assertEquals(RepoDocumentContext("github", ".github", "commit-123", "profile/README.md"), readme.context)
        assertFalse(readme.isTruncated)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("OrgProfileReadme"))
        assertTrue(body.contains("profile/README.md"))
        assertFalse(body.contains("main:README.md"))
    }

    @Test fun optionalOrganizationReadmeMissingPrivateInternalOrBlankStaysAbsent() = runTest(accounts.context) {
        val responses = listOf(
            """{"data":{"repository":null}}""",
            """{"data":{"repository":{"visibility":"PUBLIC","defaultBranchRef":null}}}""",
            """{"data":{"repository":{"visibility":"PUBLIC","defaultBranchRef":{"target":{"__typename":"Commit","oid":"abc","file":null}}}}}""",
            orgReadmeBody(visibility = "PRIVATE"),
            orgReadmeBody(visibility = "INTERNAL"),
            orgReadmeBody(text = " \n"),
            """{"data":{"repository":null},"errors":[{"type":"NOT_FOUND","message":"missing .github","path":["repository"]}]}""",
        )
        for (response in responses) {
            server.enqueue(MockResponse().setBody(response))
            assertEquals(response, ApiResult.Success(null), repository.getOrgProfileReadme("github"))
        }
    }

    @Test fun organizationReadmeKeepsTruncationAndReportsInvalidContent() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(orgReadmeBody(truncated = true)))
        val partial = repository.getOrgProfileReadme("github") as ApiResult.Success
        assertTrue(partial.data!!.isTruncated)
        server.enqueue(MockResponse().setBody(orgReadmeBody(binary = true)))
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.repo_readme_unsupported)), repository.getOrgProfileReadme("github"))
        server.enqueue(MockResponse().setBody(orgReadmeBody(text = null)))
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.repo_readme_invalid)), repository.getOrgProfileReadme("github"))
    }

    @Test fun optionalOrganizationReadmeDoesNotHidePermissionOrTransportErrors() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":null},"errors":[{"type":"FORBIDDEN","message":"denied","path":["repository"]}]}"""))
        assertTrue(repository.getOrgProfileReadme("github") is ApiResult.Failure)
        server.enqueue(MockResponse().setBody("""{"data":{"repository":null},"errors":[{"type":"NOT_FOUND","message":"missing"},{"type":"FORBIDDEN","message":"denied"}]}"""))
        assertTrue(repository.getOrgProfileReadme("github") is ApiResult.Failure)
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertTrue(repository.getOrgProfileReadme("github") is ApiResult.Failure)
    }

}
