package com.gitmob.app.core.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gitmob.app.data.repository.LauncherShortcutRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShortcutPinnedReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: LauncherShortcutRepository

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "gitmob-shortcut" || uri.host != "pinned" || uri.pathSegments.size != 2) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { repository.confirmPinned(uri.pathSegments[0], uri.pathSegments[1]) }
            finally { pending.finish() }
        }
    }
}
