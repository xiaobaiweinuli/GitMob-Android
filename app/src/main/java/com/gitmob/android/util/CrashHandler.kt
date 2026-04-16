package com.gitmob.android.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器
 * - 拦截主线程 & 子线程崩溃，写入 files/logs/crash_yyyy-MM-dd.log
 * - 同时转发给原系统处理器（保证系统崩溃弹窗正常显示）
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var crashDir: File? = null
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun install(context: Context) {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        crashDir = context.getExternalFilesDir("logs").also { it?.mkdirs() }
        LogManager.i("CrashHandler", "崩溃捕获器已安装，目录：${crashDir?.absolutePath}")
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            val now = Date()
            val msg = buildString {
                append("==================== CRASH ====================\n")
                append("时间: ${timeFmt.format(now)}\n")
                append("线程: ${thread.name} (id=${thread.threadId()})\n")
                append("异常: ${ex::class.java.name}: ${ex.message}\n\n")
                append("堆栈跟踪:\n")
                append(ex.stackTraceToString())
                var cause = ex.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    append("\nCaused by: ${cause::class.java.name}: ${cause.message}\n")
                    append(cause.stackTraceToString())
                    cause = cause.cause; depth++
                }
                append("\n===============================================\n")
            }
            // 写入崩溃日志文件
            crashDir?.let { dir ->
                File(dir, "crash_${dateFmt.format(now)}.log").appendText(msg)
            }
            // 同步写入 LogManager 内存队列（确保日志查看器可见）
            LogManager.e("CRASH", "崩溃捕获：${ex::class.java.simpleName}: ${ex.message}", ex)
            android.util.Log.e("GitMob_CRASH", msg)
        } catch (_: Exception) {}

        // 转发给系统（显示崩溃弹窗）
        defaultHandler?.uncaughtException(thread, ex)
    }
}
