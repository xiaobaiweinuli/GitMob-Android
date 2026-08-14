package com.gitmob.app.navigation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gitmob.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint

const val EXTRA_DEEP_LINK_DESTINATION = "extra_deep_link_destination"

/**
 * 接收 github.com 链接的落地页（对应 Manifest 里不带 autoVerify 的 Intent Filter，
 * 用户点链接时系统会弹"选择打开方式"，选中本 App 才会走到这里）。
 * 只负责解析 URL、转发给 MainActivity 的 NavController，自己不承载 UI。
 */
@AndroidEntryPoint
class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val destination = uri?.let { DeepLinkRouter.parse(it) } ?: DeepLinkDestination.Unsupported

        if (destination == DeepLinkDestination.Unsupported) {
            uri?.let { startActivity(Intent(Intent.ACTION_VIEW, it).apply { setPackage(null) }) }
            finish()
            return
        }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_DEEP_LINK_DESTINATION, destination)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}
