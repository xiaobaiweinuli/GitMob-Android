package com.gitmob.app.core.download

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a GitHub download URL to an external browser. The browser owns
 * the transfer, notification, file naming, retry, and APK installation handoff.
 */
@Singleton
class ExternalDownloadLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun open(url: String) {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.userInfo.isNullOrEmpty()) {
            throw UserVisibleException(R.string.download_address_unavailable)
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            selector = Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN,
                Intent.CATEGORY_APP_BROWSER,
            ).selector
        }
        try {
            // Do not preflight resolveActivity(): package visibility can report a false negative.
            // Resolve through browser entries to avoid matching GitMob's link filters.
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw UserVisibleException(R.string.download_external_app_unavailable)
        } catch (_: SecurityException) {
            throw UserVisibleException(R.string.download_external_app_unavailable)
        }
    }
}
