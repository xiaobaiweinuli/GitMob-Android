package com.gitmob.app.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gitmob.app.MainActivity

const val EXTRA_DEEP_LINK_DESTINATION = "extra_deep_link_destination"

/** 接收 GitHub / Gist 外部链接，解析后转交主界面；系统是否直接打开由链接设置决定。 */
class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val destination = uri?.let { DeepLinkRouter.parse(it) } ?: DeepLinkDestination.Unsupported
        if (destination == DeepLinkDestination.Unsupported) {
            if (uri != null) openInBrowser(uri)
        } else {
            openMain(destination)
        }
        finish()
    }

    private fun openInBrowser(uri: Uri) {
        // 只按浏览器入口解析，避免 ACTION_VIEW 再次命中本 Activity 的域名过滤器。
        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).selector
        }
        try {
            startActivity(browserIntent)
        } catch (_: ActivityNotFoundException) {
            openMain(DeepLinkDestination.BrowserUnavailable)
        } catch (_: SecurityException) {
            openMain(DeepLinkDestination.BrowserUnavailable)
        }
    }

    private fun openMain(destination: DeepLinkDestination) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_DEEP_LINK_DESTINATION, destination)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }
}
