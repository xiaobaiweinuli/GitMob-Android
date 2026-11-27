package com.gitmob.app.navigation

import android.net.Uri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CompareDeepLinksTest {
    private fun parse(path: String) = DeepLinkRouter.parse(Uri.parse("https://github.com/$path"))
    private val unsupported = DeepLinkRequest.Direct(DeepLinkDestination.Unsupported)

    @Test fun siteNamespacesAreExcludedBeforeAllRepositoryPaths() {
        for (root in listOf("trending", "explore", "collections", "topics", "marketplace", "settings", "notifications",
            "login", "logout", "signup", "join", "new", "organizations", "features", "pricing", "sponsors", "codespaces", "copilot")) {
            for (suffix in listOf("", "/", "/kotlin", "/repo/blob/main/README.md", "/repo/tree/main", "/repo/commits/main", "/repo/compare/a...b")) {
                assertEquals(root + suffix, unsupported, parse(root + suffix))
            }
        }
        assertEquals(unsupported, parse("Trending/kotlin?since=daily"))
        assertEquals(unsupported, parse("%65xplore"))
    }

    @Test fun supportedProfilesRepositoriesSearchAndOrganizationRootsStillRoute() {
        assertEquals(DeepLinkRequest.Direct(DeepLinkDestination.Profile("octocat")), parse("octocat"))
        assertEquals(RepoDetailRoute("octocat", "trending"), parse("octocat/trending").toRoute())
        assertEquals(ProfileRoute("github"), parse("orgs/github").toRoute())
        assertEquals(SearchRoute(query = "kotlin"), parse("search?q=kotlin").toRoute())
        for (path in listOf("orgs", "orgs/github/people", "orgs/github/repositories", "orgs/github/tree/main", "search/x/blob/main/a")) {
            assertEquals(path, unsupported, parse(path))
        }
        assertTrue(parse("octocat/repo/blob/main/README.md") is DeepLinkRequest.RepositoryContent)
    }

    @Test fun comparisonEndpointsDecodeExactlyOnceAndPreserveTheOriginalUrl() {
        val cases = listOf(
            "0508f55ed13945ebcd1dbfd221ec6f2ec22b3475...b251c8711e451197c8ab9dc17971e91e22d73888" to
                ("0508f55ed13945ebcd1dbfd221ec6f2ec22b3475" to "b251c8711e451197c8ab9dc17971e91e22d73888"),
            "0508f55...b251c87" to ("0508f55" to "b251c87"),
            "main...feature/docs" to ("main" to "feature/docs"),
            "release%2Fv1...feature%2Fdocs" to ("release/v1" to "feature/docs"),
            "v1.0...tags/v2.0" to ("v1.0" to "tags/v2.0"),
            "0508f55...main" to ("0508f55" to "main"),
            "tags/v1...b251c87" to ("tags/v1" to "b251c87"),
            "feature%252Fdocs...feature+C%2B%2B" to ("feature%2Fdocs" to "feature+C++"),
            "main%2E%2E%2Edev" to ("main" to "dev"),
        )
        for ((tail, refs) in cases) {
            val url = "https://github.com/ReChronoRain/HyperCeiler/compare/$tail?expand=1"
            val request = DeepLinkRouter.parse(Uri.parse(url))
            val route = RepoCompareRoute("ReChronoRain", "HyperCeiler", refs.first, refs.second, url)
            assertEquals(url, route, request.toRoute())
            assertEquals(route, Json.decodeFromString<RepoCompareRoute>(Json.encodeToString(route)))
        }
    }

    @Test fun deferredAndMalformedComparisonsRemainBrowserLinks() {
        for (tail in listOf("", "main", "main..dev", "...dev", "main...", "main...dev...other", "main....dev",
            "main...user:dev", "user:main...dev", "main^...dev", "main~1...dev", "main...dev.diff", "main...dev.patch",
            "main...dev?expand=0", "main...dev?expand=1&expand=1", "main...dev?foo=1", "main...dev#diff-123",
            "main...dev?expand=1&foo=2", "main.../dev", "main...dev/", "main...feature//docs", "main...tags/",
            "main...dev%5E", "main...dev%3Abranch", "main...dev%20branch", "main...a@%7B1%7D")) {
            assertEquals(tail, unsupported, parse("o/r/compare/$tail"))
        }
    }

    @Test fun repositoryAddressUpdatesAndSerializationPreserveReadOnlyAndOriginalLink() {
        val comparison = RepoCompareRoute("old", "repo", "main", "tags/v1", "https://github.com/old/repo/compare/main...tags/v1")
        assertEquals(comparison.copy(owner = "new", name = "renamed"), comparison.replaceRepositoryAddress("old", "repo", "new", "renamed"))
        val commit = RepoCommitDetailRoute("old", "repo", "a".repeat(40), readOnly = true)
        assertEquals(commit, Json.decodeFromString<RepoCommitDetailRoute>(Json.encodeToString(commit)))
        assertTrue((commit.replaceRepositoryAddress("old", "repo", "new", "renamed") as RepoCommitDetailRoute).readOnly)
        assertFalse(Json.decodeFromString<RepoCommitDetailRoute>("""{"owner":"o","name":"r","sha":"abc"}""").readOnly)
    }
}
