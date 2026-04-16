package com.gitmob.android.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 接收下载通知里的取消操作 */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val taskId = intent.getIntExtra("taskId", -1)
        if (taskId == -1) return
        if (intent.action == "com.gitmob.DOWNLOAD_CANCEL") {
            GmDownloadManager.cancel(ctx, taskId)
        }
    }
}
