package com.gitmob.app.navigation

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.safeCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun UriHandler.openExternalUrl(rawUrl: String): ApiResult<Unit> {
    val target = if (rawUrl.startsWith("http://", ignoreCase = true) ||
        rawUrl.startsWith("https://", ignoreCase = true)
    ) rawUrl else "https://$rawUrl"
    return openExternalUri(target)
}

/** Await the actual launch, preserving qualified schemes and coroutine cancellation. */
internal suspend fun UriHandler.openExternalUri(uri: String): ApiResult<Unit> = safeCall {
    try {
        if (this is ExternalUriHandler) openUriAndAwait(uri) else openUri(uri)
    } catch (_: ActivityNotFoundException) {
        throw UserVisibleException(R.string.external_link_open_failed)
    } catch (_: SecurityException) {
        throw UserVisibleException(R.string.external_link_open_failed)
    } catch (_: IllegalArgumentException) {
        throw UserVisibleException(R.string.external_link_open_failed)
    }
}

/** Compose callbacks are synchronous; the composition owns each pending launch. */
internal class ExternalUriHandler(
    private val launcher: ExternalUriLauncher,
    private val scope: CoroutineScope,
    private val epoch: AccountEpoch,
    private val errors: ErrorEventBus,
) : UriHandler {
    override fun openUri(uri: String) {
        scope.launch(AccountContext(epoch)) {
            when (val result = openExternalUri(uri)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> errors.emit(result.error, epoch)
            }
        }
    }

    suspend fun openUriAndAwait(uri: String) {
        epoch.ensureCurrent()
        // Awaited calls can originate in a composition scope without AccountContext.
        withContext(AccountContext(epoch)) { launcher.openUri(uri) }
    }
}
