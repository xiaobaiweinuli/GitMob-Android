package com.gitmob.app.navigation

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler

/** Report only launch failures; cancellation and programming errors must propagate. */
internal fun UriHandler.tryOpenExternalUrl(rawUrl: String): Boolean {
    val target = if (rawUrl.startsWith("http://", ignoreCase = true) ||
        rawUrl.startsWith("https://", ignoreCase = true)
    ) rawUrl else "https://$rawUrl"
    return try {
        openUri(target)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        // AndroidUriHandler wraps a missing activity in IllegalArgumentException.
        false
    }
}
