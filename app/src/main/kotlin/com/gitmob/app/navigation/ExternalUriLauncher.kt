package com.gitmob.app.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.network.RawUrlPolicy
import com.gitmob.app.core.preferences.RawAccelerationPreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

internal val LocalExternalUriLauncher = staticCompositionLocalOf<ExternalUriLauncher?> { null }

/** Mapping happens after internal navigation has examined the original URI. */
@Singleton
class ExternalUriLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RawAccelerationPreferenceStore,
    private val policy: RawUrlPolicy,
) {
    internal suspend fun destination(uri: String): String =
        if (policy.accepts(uri)) policy.rewrite(uri, preferences.initialize().activePrefix) else uri

    suspend fun openUri(uri: String) {
        val target = destination(uri)
        ensureLaunchActive()
        launch(target, browser = false)
    }

    suspend fun openBrowser(uri: String): Boolean {
        val target = destination(uri)
        ensureLaunchActive()
        return openResolvedBrowser(target)
    }

    private suspend fun ensureLaunchActive() {
        currentCoroutineContext().ensureActive()
        AccountContext.current()?.ensureCurrent()
    }

    /** Launch a resolved address; GitHub/Gist deep-link fallbacks do not require raw settings. */
    fun openResolvedBrowser(uri: String): Boolean = try {
        launch(uri, browser = true)
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
      catch (_: IllegalArgumentException) { false }

    private fun launch(uri: String, browser: Boolean) {
        val target = uri.toUri()
        require(target.scheme?.lowercase() in setOf("https", "http", "mailto") && target.userInfo.isNullOrEmpty())
        val intent = Intent(Intent.ACTION_VIEW, target).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (browser) selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).selector
        }
        context.startActivity(intent)
    }
}
