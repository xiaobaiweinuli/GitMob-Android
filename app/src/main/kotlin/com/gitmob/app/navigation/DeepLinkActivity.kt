package com.gitmob.app.navigation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gitmob.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

const val EXTRA_DEEP_LINK_REQUEST = "extra_deep_link_request"

/** 接收 GitHub / Gist 外部链接，解析后转交主界面；系统是否直接打开由链接设置决定。 */
@AndroidEntryPoint
class DeepLinkActivity : ComponentActivity() {
    @Inject lateinit var externalUris: ExternalUriLauncher
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val destination = uri?.let { DeepLinkRouter.parse(it) } ?: DeepLinkRequest.Direct(DeepLinkDestination.Unsupported)
        if (destination == DeepLinkRequest.Direct(DeepLinkDestination.Unsupported)) {
            if (uri != null && !externalUris.openResolvedBrowser(uri.toString())) {
                openMain(DeepLinkRequest.Direct(DeepLinkDestination.BrowserUnavailable))
            }
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
