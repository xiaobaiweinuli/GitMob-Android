package com.gitmob.app.core.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands GitHub's short-lived signed URL to an external browser. The browser owns
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
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            throw UserVisibleException(R.string.download_browser_unavailable)
        }
        runCatching { context.startActivity(intent) }
            .getOrElse { throw UserVisibleException(R.string.download_browser_unavailable) }
    }
}
