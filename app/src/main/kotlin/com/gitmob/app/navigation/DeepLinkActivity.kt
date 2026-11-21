package com.gitmob.app.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gitmob.app.MainActivity

const val EXTRA_DEEP_LINK_REQUEST = "extra_deep_link_request"

/** 接收 GitHub / Gist 外部链接，解析后转交主界面；系统是否直接打开由链接设置决定。 */
class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val destination = uri?.let { DeepLinkRouter.parse(it) } ?: DeepLinkRequest.Direct(DeepLinkDestination.Unsupported)
        if (destination == DeepLinkRequest.Direct(DeepLinkDestination.Unsupported)) {
            if (uri != null && !tryOpenBrowser(uri)) openMain(DeepLinkRequest.Direct(DeepLinkDestination.BrowserUnavailable))
        } else {
            openMain(destination)
        }
        finish()
    }

    private fun openMain(destination: DeepLinkRequest) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_DEEP_LINK_REQUEST, destination)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }
}

/** Resolve a browser entry explicitly so a GitHub URL cannot reopen GitMob. */
internal fun Activity.tryOpenBrowser(uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).selector
    }
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
