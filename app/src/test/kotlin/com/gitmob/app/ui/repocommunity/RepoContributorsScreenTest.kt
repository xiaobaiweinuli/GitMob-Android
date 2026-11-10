package com.gitmob.app.ui.repocommunity

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.testutil.TestAccessTokenProvider
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.repository.RepoCommunityRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoContributorsScreenTest {
    private val compose = createComposeRule()
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var viewModel: RepoContributorsViewModel
    private var openedLogin: String? = null

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val api = GHApiClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            tokenProvider = object : TestAccessTokenProvider {
                override suspend fun getToken() = "fake-token"
            },
            restBaseUrl = server.url("/").toString().removeSuffix("/"),
        )
        viewModel = RepoContributorsViewModel(RepoCommunityRepository(api), ErrorEventBus(), accounts.session)
    }

    @After fun teardown() {
        viewModel.viewModelScope.cancel()
        server.shutdown()
    }

    @Test fun sameNameAnonymousContributorsRenderAsSeparateRows() {
        // Real Telegram response structure, with author emails replaced by test addresses.
        showContributors("""[
            {"name":"DrKLO","email":"author-a@example.com","type":"Anonymous","contributions":318},
            {"name":"DrKLO","email":"author-b@example.com","type":"Anonymous","contributions":6}
        ]""", 2)

        compose.onAllNodesWithText("DrKLO").assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.contributors_count, 318)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.contributors_count, 6)).assertIsDisplayed()
        assertEquals(listOf(318, 6), viewModel.state.value.items.map { it.contributions })
        assertEquals(listOf(null, null), viewModel.state.value.items.map { it.login })
        assertFalse(viewModel.state.value.hasNextPage)
        assertEquals(
            "/repos/xiaobaiweinuli/Telegram/contributors?per_page=${PageSize.CONTRIBUTORS}&page=1&anon=1",
            server.takeRequest().path,
        )
    }

    @Test fun anonymousDisplayNameDoesNotBecomeAProfileLink() {
        showContributors("""[
            {"name":"DrKLO","email":"author-a@example.com","type":"Anonymous","contributions":318}
        ]""", 1)

        compose.onNodeWithText("DrKLO").assertIsNotEnabled().performClick()
        assertNull(openedLogin)
        assertNull(viewModel.state.value.items.single().login)
    }

    @Test fun registeredContributorStillOpensTheRealLogin() {
        showContributors("""[
            {"id":69369,"login":"DrKLO","name":"Display name","html_url":"https://github.com/DrKLO","type":"User","contributions":78}
        ]""", 1)

        compose.onNodeWithText("DrKLO").assertIsEnabled().performClick()
        assertEquals("DrKLO", openedLogin)
    }

    @Test fun recordsWithoutIdentityFieldsRemainVisible() {
        showContributors("""[
            {"name":"Same author","type":"Anonymous","contributions":2},
            {"name":"Same author","type":"Anonymous","contributions":1}
        ]""", 2)

        compose.onAllNodesWithText("Same author").assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.contributors_count, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.contributors_count, 1)).assertIsDisplayed()
    }

    @Test fun nextPageKeepsSameNameAnonymousAndRegisteredContributors() {
        val firstPage = buildList {
            add("""{"name":"DrKLO","email":"author-a@example.com","type":"Anonymous","contributions":318}""")
            repeat(PageSize.CONTRIBUTORS - 1) { index ->
                add("""{"id":${1000 + index},"login":"user$index","type":"User","contributions":10}""")
            }
        }.joinToString(prefix = "[", postfix = "]")
        showContributors(firstPage, PageSize.CONTRIBUTORS)
        server.enqueue(MockResponse().setBody("""[
            {"name":"DrKLO","email":"author-b@example.com","type":"Anonymous","contributions":6},
            {"id":69369,"login":"DrKLO","html_url":"https://github.com/DrKLO","type":"User","contributions":1}
        ]"""))

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(PageSize.CONTRIBUTORS)
        awaitItems(PageSize.CONTRIBUTORS + 2)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(PageSize.CONTRIBUTORS)
        compose.onNode(hasText("DrKLO") and hasText(context.getString(R.string.contributors_count, 6)))
            .assertIsNotEnabled()
        compose.onNode(hasText("DrKLO") and hasText(context.getString(R.string.contributors_count, 1)))
            .assertIsEnabled().performClick()
        assertEquals("DrKLO", openedLogin)
        assertEquals(318, viewModel.state.value.items.first().contributions)
        assertEquals(2, viewModel.state.value.page)
        assertFalse(viewModel.state.value.hasNextPage)
        server.takeRequest()
        assertEquals(
            "/repos/xiaobaiweinuli/Telegram/contributors?per_page=${PageSize.CONTRIBUTORS}&page=2&anon=1",
            server.takeRequest().path,
        )
        assertEquals(2, server.requestCount)
    }

    private fun showContributors(json: String, count: Int) {
        server.enqueue(MockResponse().setBody(json))
        compose.setContent {
            MaterialTheme {
                RepoContributorsScreen(
                    owner = "xiaobaiweinuli",
                    name = "Telegram",
                    onBack = {},
                    onOwnerClick = {},
                    onRepositoryClick = { _, _ -> },
                    onUserClick = { openedLogin = it },
                    viewModel = viewModel,
                )
            }
        }
        awaitItems(count)
    }

    private fun awaitItems(count: Int) {
        compose.waitUntil(5_000) { viewModel.state.value.items.size == count }
        compose.waitForIdle()
    }
}
