package com.gitmob.app.navigation

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExternalLinksTest {
    @Test fun qualifiedDocumentUrisPreserveSchemes() = runTest {
        var target: String? = null
        val handler = object : UriHandler { override fun openUri(uri: String) { target = uri } }
        for (uri in listOf("mailto:hello@example.com?subject=Compare", "http://example.com/a", "https://example.com/a#b")) {
            assertEquals(ApiResult.Success(Unit), handler.openExternalUri(uri))
            assertEquals(uri, target)
        }
    }

    @Test fun normalizesUrlsAndReportsExpectedLaunchFailures() = runTest {
        var target: String? = null
        val handler = object : UriHandler { override fun openUri(uri: String) { target = uri } }
        assertEquals(ApiResult.Success(Unit), handler.openExternalUrl("github.com/o/r"))
        assertEquals("https://github.com/o/r", target)
        assertEquals(ApiResult.Success(Unit), handler.openExternalUrl("https://example.com/page"))
        assertEquals("https://example.com/page", target)
        for (failure in listOf(ActivityNotFoundException(), SecurityException(), IllegalArgumentException())) {
            val broken = object : UriHandler { override fun openUri(uri: String): Unit = throw failure }
            val expected = ApiResult.Failure(ApiError.UserVisible(R.string.external_link_open_failed))
            assertEquals(expected, broken.openExternalUrl("https://example.com"))
            assertEquals(expected, broken.openExternalUri("mailto:hello@example.com"))
        }
    }

    @Test fun cancellationIsNeverSwallowedAsALaunchFailure() = runTest {
        val cancelled = object : UriHandler { override fun openUri(uri: String): Unit = throw CancellationException() }
        assertTrue(runCatching { cancelled.openExternalUrl("https://example.com") }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { cancelled.openExternalUri("mailto:hello@example.com") }.exceptionOrNull() is CancellationException)
    }
}
