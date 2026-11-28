package com.gitmob.app.core.download

import androidx.core.net.toUri
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.navigation.ExternalUriLauncher
import javax.inject.Inject
import javax.inject.Singleton

/** The browser owns the transfer, notification, file naming and installation handoff. */
@Singleton
class ExternalDownloadLauncher @Inject constructor(private val launcher: ExternalUriLauncher) {
    suspend fun open(url: String) {
        val uri = url.toUri()
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.userInfo.isNullOrEmpty()) {
            throw UserVisibleException(R.string.download_address_unavailable)
        }
        if (!launcher.openBrowser(url)) throw UserVisibleException(R.string.download_external_app_unavailable)
    }
}
