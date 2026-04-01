package com.gitmob.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gitmob.android.api.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 下载任务状态 */
sealed class DownloadStatus {
    object Idle      : DownloadStatus()
    data class Progress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadStatus()
    data class Success(val file: File) : DownloadStatus()
    data class Failed(val error: String) : DownloadStatus()
    object Cancelled : DownloadStatus()
}

/** 单次下载任务 */
data class DownloadTask(
    val id: Int,
    val filename: String,
    val url: String,
    val statusFlow: MutableStateFlow<DownloadStatus> = MutableStateFlow(DownloadStatus.Idle),
    var job: Job? = null,
)

/**
 * GitMob 下载管理器
 *
 * 修复要点：
 *  1. GitHub API 返回 302，Location 指向 S3/Azure 预签名 URL。
 *     OkHttp 默认跟随重定向时会携带 Authorization 头，S3 会返回 403/415。
 *     修复：手动处理第一跳（读 Location），第二跳用裸客户端不带 Auth 头。
 *
 *  2. 去掉"暂停/继续"（无 Range 断点续传支持），改为"取消"。
 *
 *  3. 通知分两个 Channel：
 *     - 进度 Channel（IMPORTANCE_LOW）：静默
 *     - 完成/失败 Channel（IMPORTANCE_DEFAULT）：有提示音
 */
object GmDownloadManager {

    private const val CHANNEL_PROGRESS = "gitmob_dl_progress"
    private const val CHANNEL_RESULT   = "gitmob_dl_result"

    private val notifId = AtomicInteger(10000)
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tasks: ConcurrentHashMap<Int, DownloadTask> = ConcurrentHashMap()

    /** 不自动跟随重定向的裸客户端，用于获取 S3 Location */
    private val noRedirectClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** 裸客户端：不带 GitHub token，用于第二跳 S3/Azure 预签名 URL */
    private val bareClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun initChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // 进度：静默
        val ch1 = NotificationChannel(CHANNEL_PROGRESS, "下载进度", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示下载中的进度"
            setSound(null, null)
            enableVibration(false)
        }

        // 结果：有提示音
        val ch2 = NotificationChannel(CHANNEL_RESULT, "下载完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "下载成功或失败时的提示"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(true)
        }

        nm.createNotificationChannels(listOf(ch1, ch2))
    }

    /** 开始下载（返回任务 id） */
    fun download(ctx: Context, url: String, filename: String): Int {
        initChannels(ctx)
        val id = notifId.getAndIncrement()
        val task = DownloadTask(id, filename, url)
        tasks[id] = task

        task.job = scope.launch {
            doDownload(ctx, task)
            tasks.remove(id)
        }
        return id
    }

    fun cancel(ctx: Context, id: Int) {
        tasks[id]?.job?.cancel()
        tasks.remove(id)
        NotificationManagerCompat.from(ctx).cancel(id)
    }

    fun getTask(id: Int): DownloadTask? = tasks[id]
    fun statusOf(id: Int): StateFlow<DownloadStatus>? = tasks[id]?.statusFlow

    // ── 实际下载（两跳策略）────────────────────────────────────────────

    private suspend fun doDownload(ctx: Context, task: DownloadTask) {
        task.statusFlow.value = DownloadStatus.Progress(0, 0, 0)
        postNotifProgress(ctx, task, 0)

        try {
            // 判断是否是 GitHub API URL 还是直接的下载 URL
            val isGithubApiUrl = task.url.contains("api.github.com/repos/")
            
            if (!isGithubApiUrl) {
                // 直接下载，不需要 GitHub API 认证
                val req = Request.Builder().url(task.url).get().build()
                val resp = bareClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    error("HTTP ${resp.code}: ${resp.message}")
                }
                streamToFile(ctx, task, resp)
                return
            }

            val token = ApiClient.currentToken() ?: error("未登录")

            // 判断是 release asset 还是 artifact
            val isReleaseAsset = task.url.contains("/releases/assets/")
            val isArtifact = task.url.contains("/actions/artifacts/")

            // 对于 GitHub API URL，根据类型使用不同的 Accept 头
            // - release asset: application/octet-stream
            // - artifact: application/vnd.github+json
            val acceptHeader = if (isArtifact) "application/vnd.github+json" else "application/octet-stream"
            
            val firstReq = Request.Builder()
                .url(task.url)
                .header("Authorization", "Bearer $token")
                .header("Accept", acceptHeader)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            var firstResp = noRedirectClient.newCall(firstReq).execute()
            
            when (firstResp.code) {
                302, 301, 307, 308 -> {
                    val location = firstResp.header("Location") ?: error("重定向但无 Location 头")
                    firstResp.close()
                    
                    // 第二跳：不带 Authorization，直接请求 S3/Azure 预签名 URL
                    val secondReq = Request.Builder().url(location).get().build()
                    val secondResp = bareClient.newCall(secondReq).execute()
                    
                    if (!secondResp.isSuccessful) {
                        secondResp.close()
                        error("HTTP ${secondResp.code}: ${secondResp.message}")
                    }
                    streamToFile(ctx, task, secondResp)
                }
                200 -> {
                    // API 直接返回文件内容
                    streamToFile(ctx, task, firstResp)
                }
                else -> {
                    firstResp.close()
                    error("HTTP ${firstResp.code}: ${firstResp.message}")
                }
            }

        } catch (e: CancellationException) {
            NotificationManagerCompat.from(ctx).cancel(task.id)
        } catch (e: Exception) {
            val msg = e.message ?: "下载失败"
            task.statusFlow.value = DownloadStatus.Failed(msg)
            postNotifFailed(ctx, task, msg)
        }
    }

    private suspend fun streamToFile(ctx: Context, task: DownloadTask, resp: okhttp3.Response) {
        val body  = resp.body ?: error("响应体为空")
        val total = body.contentLength()
        var written = 0L
        val dest = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            task.filename)

        body.byteStream().use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                    written += n
                    val pct = if (total > 0) (written * 100 / total).toInt() else -1
                    task.statusFlow.value = DownloadStatus.Progress(pct, written, total)
                    if (pct >= 0 && pct % 5 == 0) postNotifProgress(ctx, task, pct)
                }
            }
        }
        task.statusFlow.value = DownloadStatus.Success(dest)
        postNotifSuccess(ctx, task, dest)
    }

    // ── 通知────────────────────────────────────────────────────────

    private fun postNotifProgress(ctx: Context, task: DownloadTask, pct: Int) {
        val notif = NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText(task.filename)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, pct < 0)
            .build()

        NotificationManagerCompat.from(ctx).notify(task.id, notif)
    }

    private fun postNotifSuccess(ctx: Context, task: DownloadTask, file: File) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file),
                "*/*"
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(ctx, task.id, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notif = NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(task.filename)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(task.id, notif)
    }

    private fun postNotifFailed(ctx: Context, task: DownloadTask, msg: String) {
        val notif = NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText(msg)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(task.id, notif)
    }
}
