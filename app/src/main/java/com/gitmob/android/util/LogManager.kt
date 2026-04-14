package com.gitmob.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** 日志等级 */
enum class LogLevel(val tag: String, val priority: Int) {
    VERBOSE("V", Log.VERBOSE),
    DEBUG  ("D", Log.DEBUG),
    INFO   ("I", Log.INFO),
    WARN   ("W", Log.WARN),
    ERROR  ("E", Log.ERROR),
    NONE   ("_", 99),           // 关闭写入
}

data class LogEntry(
    val time: String,
    val level: LogLevel,
    val tag: String,
    val msg: String,
)

/**
 * GitMob 日志管理器
 * - 内存缓存最近 500 条（供 UI 实时展示）
 * - 写入 files/logs/gitmob_YYYY-MM-DD.log（每天一个文件）
 * - 最多保留 7 天
 */
object LogManager {

    private const val APP_TAG = "GitMob"
    private const val MAX_MEM = 500
    private const val MAX_DAYS = 7

    @Volatile var minLevel: LogLevel = LogLevel.DEBUG
        private set

    private val queue = ConcurrentLinkedQueue<LogEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var logDir: File? = null

    fun init(context: Context, level: LogLevel = LogLevel.DEBUG) {
        minLevel = level
        logDir = context.getExternalFilesDir("logs").also { it?.mkdirs() }
        pruneOldLogs()
        i("LogManager", "日志系统初始化，等级：${level.name}，目录：${logDir?.absolutePath}")
    }

    fun setLevel(level: LogLevel) {
        minLevel = level
        i("LogManager", "日志等级变更为 ${level.name}")
    }

    // ─── 快捷方法 ────────────────────────────────────────────
    fun v(tag: String, msg: String) = write(LogLevel.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = write(LogLevel.DEBUG,   tag, msg)
    fun i(tag: String, msg: String) = write(LogLevel.INFO,    tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null) =
        write(LogLevel.WARN, tag, if (e != null) "$msg\n${e.stackTraceToString().take(400)}" else msg)
    fun e(tag: String, msg: String, e: Throwable? = null) =
        write(LogLevel.ERROR, tag, if (e != null) "$msg\n${e.stackTraceToString().take(400)}" else msg)

    fun write(level: LogLevel, tag: String, msg: String) {
        if (level.priority < minLevel.priority) return
        val entry = LogEntry(time = timeFmt.format(Date()), level = level, tag = tag, msg = msg)
        // Android logcat
        Log.println(level.priority, "$APP_TAG/$tag", msg)
        // 内存队列
        queue.add(entry)
        while (queue.size > MAX_MEM) queue.poll()
        // 写文件（非阻塞；若目录未初始化则跳过）
        logDir?.let { dir ->
            try {
                val file = File(dir, "gitmob_${fileFmt.format(Date())}.log")
                val line = "${entry.time} [${entry.level.tag}] ${entry.tag}: ${entry.msg}\n"
                file.appendText(line)
            } catch (_: Exception) {}
        }
    }

    /** 内存中最近的日志（最新在前）*/
    fun recent(maxCount: Int = 200): List<LogEntry> =
        queue.toList().takeLast(maxCount).reversed()

    /** 当前日志文件 File 对象（可能不存在） */
    fun currentLogFile(): File? {
        val dir = logDir ?: return null
        return File(dir, "gitmob_${fileFmt.format(Date())}.log")
    }

    /** 所有日志文件列表 */
    fun logFiles(): List<File> = logDir?.listFiles()
        ?.filter { it.name.endsWith(".log") }
        ?.sortedByDescending { it.name } ?: emptyList()

    /** 清空当天日志文件和内存队列 */
    fun clearToday() {
        currentLogFile()?.delete()
        queue.clear()
        i("LogManager", "日志已清空")
    }

    /** 删除 7 天以上的旧日志 */
    private fun pruneOldLogs() {
        logDir?.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_DAYS)
            ?.forEach { it.delete() }
    }
}
