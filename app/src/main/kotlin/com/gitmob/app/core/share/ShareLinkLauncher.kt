package com.gitmob.app.core.share

import android.content.Context
import android.content.Intent
import com.gitmob.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 统一使用系统分享面板，不在 App 内复制链接到各个页面。 */
@Singleton
class ShareLinkLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun share(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.conversation_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
