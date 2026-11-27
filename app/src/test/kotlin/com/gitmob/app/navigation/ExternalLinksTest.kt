package com.gitmob.app.navigation

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ExternalLinksTest {
    @Test fun qualifiedDocumentUrisPreserveSchemeAndExistingLaunchFailureSemantics() {
        var target: String? = null
        val handler = object : UriHandler { override fun openUri(uri: String) { target = uri } }
        for (uri in listOf("mailto:hello@example.com?subject=Compare", "http://example.com/a", "https://example.com/a#b")) {
            assertTrue(handler.tryOpenExternalUri(uri))
            assertEquals(uri, target)
        }
        assertTrue(handler.tryOpenExternalUrl("example.com"))
        assertEquals("https://example.com", target)
        for (failure in listOf(ActivityNotFoundException(), SecurityException(), IllegalArgumentException())) {
            val broken = object : UriHandler { override fun openUri(uri: String): Unit = throw failure }
            assertFalse(broken.tryOpenExternalUri("mailto:hello@example.com"))
        }
        val cancelled = object : UriHandler { override fun openUri(uri: String): Unit = throw CancellationException() }
        assertTrue(runCatching { cancelled.tryOpenExternalUri("mailto:hello@example.com") }.exceptionOrNull() is CancellationException)
    }

    @Test fun normalizesUrlsAndReportsExpectedLaunchFailures() {
        var target: String? = null
        val handler = object : UriHandler { override fun openUri(uri: String) { target = uri } }
        assertTrue(handler.tryOpenExternalUrl("github.com/o/r"))
        assertEquals("https://github.com/o/r", target)
        assertTrue(handler.tryOpenExternalUrl("https://example.com/page"))
        assertEquals("https://example.com/page", target)
        for (failure in listOf(ActivityNotFoundException(), SecurityException(), IllegalArgumentException())) {
            val broken = object : UriHandler { override fun openUri(uri: String): Unit = throw failure }
            assertFalse(broken.tryOpenExternalUrl("https://example.com"))
        }
    }
    @Test fun cancellationIsNeverSwallowedAsALaunchFailure() {
        val cancelled = object : UriHandler { override fun openUri(uri: String): Unit = throw CancellationException() }
        assertTrue(runCatching { cancelled.tryOpenExternalUrl("https://example.com") }.exceptionOrNull() is CancellationException)
    }
}
