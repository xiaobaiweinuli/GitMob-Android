package com.gitmob.android.auth

import com.gitmob.android.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封装 Root 操作，使用 Runtime.getRuntime().exec() 直接执行 su 命令
 * 支持 Magisk、KernelSU、APatch 等多种 Root 方案
 * 首次调用 requestRoot() 时申请 root 权限
 * 基于社区主流实现方式：使用 --mount-master 或 nsenter 进行全局命名空间访问
 * 参考：APatch、Neo-Backup 等成熟项目的实现
 */
object RootManager {

    private const val TAG = "RootManager"

    /** su 执行模式：是否进入全局 mount 命名空间 */
    private enum class SuExecMode {
        UNKNOWN,
        NSENTER,       // su -c 'nsenter --mount=/proc/1/ns/mnt sh -c "...'
        MOUNT_MASTER,  // su --mount-master -c "..."
        PLAIN          // su -c "..."
    }

    /** Root 方案类型 */
    enum class RootType {
        NONE,           // 未获取 Root
        MAGISK,         // Magisk
        KERNEL_SU,      // KernelSU
        APATCH,         // APatch
        UNKNOWN         // 未知 Root 方案
    }

    /** 当前 Root 类型 */
    var rootType: RootType = RootType.NONE
        private set

    /** Root 版本信息 */
    var rootVersion: String? = null
        private set

    /** 是否已获得 root 权限 */
    var isGranted: Boolean = false
        private set

    /** 当前 su 执行模式（懒检测） */
    private var suExecMode: SuExecMode = SuExecMode.UNKNOWN

    /**
     * 由 GitMobApp 在启动时注入 DataStore 缓存的模式值（-1=未知，0=NSENTER，1=MOUNT_MASTER，2=PLAIN）。
     * 避免每次冷启动重新 fork 3~6 次进程做探测。
     */
    fun injectSuExecModeCache(cached: Int) {
        if (suExecMode != SuExecMode.UNKNOWN) return   // 已经探测过，不覆盖
        suExecMode = when (cached) {
            0 -> SuExecMode.NSENTER
            1 -> SuExecMode.MOUNT_MASTER
            2 -> SuExecMode.PLAIN
            else -> SuExecMode.UNKNOWN
        }
        if (suExecMode != SuExecMode.UNKNOWN)
            LogManager.d(TAG, "su 执行模式从缓存恢复: $suExecMode")
    }

    /** 获取当前模式的数字编码，供 GitMobApp 写入 DataStore 缓存 */
    fun getSuExecModeForPersist(): Int = when (suExecMode) {
        SuExecMode.NSENTER       -> 0
        SuExecMode.MOUNT_MASTER  -> 1
        SuExecMode.PLAIN         -> 2
        SuExecMode.UNKNOWN       -> -1
    }

    /**
     * 直接执行命令（不走 su -c 包裹），用于查询 su/magisk/ksud 本身的信息，避免递归套娃。
     */
    private fun execRaw(vararg cmds: String): Triple<String, String, Int> {
        return try {
            val process = Runtime.getRuntime().exec(cmds)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            Triple(output, error, exitCode)
        } catch (e: Exception) {
            Triple("", e.message ?: "执行异常", -1)
        }
    }

    private fun rawOutputOrEmpty(vararg cmds: String): String {
        val (out, _, code) = execRaw(*cmds)
        return if (code == 0) out else ""
    }

    /**
     * 用 root shell 检查路径是否存在（比 java.io.File.exists 更可靠：/data/adb 在非 root 下通常不可见）
     */
    private fun rootPathExists(path: String): Boolean {
        val (out, _, code) = executeSuCommand("sh", "-c", "test -e '$path' && echo 1 || echo 0")
        if (code != 0) return false
        return out.trim().lines().lastOrNull()?.trim() == "1"
    }

    /**
     * 检测系统是否提供 nsenter（Android 10+ 的 toybox 默认包含）
     * 不需要 root 即可调用，只是检查命令是否存在
     */
    private fun hasNsenter(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf(
                    "/system/bin/sh",
                    "-c",
                    "command -v nsenter >/dev/null 2>&1 || which nsenter >/dev/null 2>&1"
                )
            )
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 在指定模式下执行一次简单命令（id -u），用于探测该模式是否可用
     */
    private fun testSuMode(mode: SuExecMode): Boolean {
        val (out, _, code) = executeSuCommandInternal(mode, "id", "-u")
        val ok = code == 0 && out.trim() == "0"
        if (ok) {
            LogManager.i(TAG, "su 模式可用: $mode")
        } else {
            LogManager.d(TAG, "su 模式不可用: $mode, 输出=$out, code=$code")
        }
        return ok
    }

    /**
     * 懒加载 su 执行模式：
     * 1. 优先使用 nsenter 进入 init 全局 mount 命名空间（社区主流做法）
     * 2. 退化为 su --mount-master
     * 3. 最后退化为普通 su -c
     */
    private fun ensureSuExecMode() {
        if (suExecMode != SuExecMode.UNKNOWN) return

        // 优先尝试 nsenter（Android 10+）
        if (hasNsenter() && testSuMode(SuExecMode.NSENTER)) {
            suExecMode = SuExecMode.NSENTER
            return
        }

        // 尝试 su --mount-master（KernelSU / APatch / 新版 Magisk）
        if (testSuMode(SuExecMode.MOUNT_MASTER)) {
            suExecMode = SuExecMode.MOUNT_MASTER
            return
        }

        // 最后使用普通 su -c
        if (testSuMode(SuExecMode.PLAIN)) {
            suExecMode = SuExecMode.PLAIN
        } else {
            // 完全不可用就保持 PLAIN，后续执行会返回错误码
            suExecMode = SuExecMode.PLAIN
        }
    }

    /**
     * 检测 Root 类型
     * 基于主要文件路径检测，这是社区主流的实现方式
     * 参考：Neo-Backup、MiXplorer 等成熟项目
     */
    private fun detectRootType(): RootType {
        try {
            // 先用 root 环境检查 /data/adb 标识（非 root 下通常无权限访问，必须通过 su）
            if (rootPathExists("/data/adb/ksu")) {
                LogManager.i(TAG, "检测到 KernelSU (/data/adb/ksu)")
                return RootType.KERNEL_SU
            }
            if (rootPathExists("/data/adb/ap")) {
                LogManager.i(TAG, "检测到 APatch (/data/adb/ap)")
                return RootType.APATCH
            }
            if (rootPathExists("/data/adb/magisk")) {
                LogManager.i(TAG, "检测到 Magisk (/data/adb/magisk)")
                return RootType.MAGISK
            }

            // 再通过 su 自身版本输出识别（注意：这里不能用 executeSuCommand 去跑 su，否则会 su 套 su）
            val suText = buildString {
                append(rawOutputOrEmpty("su", "-v"))
                append('\n')
                append(rawOutputOrEmpty("su", "-V"))
            }.trim()

            if (suText.contains("KSU", ignoreCase = true) || suText.contains("KernelSU", ignoreCase = true)) {
                LogManager.i(TAG, "检测到 KernelSU (su 版本输出)")
                return RootType.KERNEL_SU
            }
            if (suText.contains("magisk", ignoreCase = true)) {
                LogManager.i(TAG, "检测到 Magisk (su 版本输出)")
                return RootType.MAGISK
            }
            if (suText.contains("apatch", ignoreCase = true) || suText.contains("APatch", ignoreCase = true)) {
                LogManager.i(TAG, "检测到 APatch (su 版本输出)")
                return RootType.APATCH
            }
            
            // 如果检测到 Root 但无法确定具体类型
            return RootType.UNKNOWN
        } catch (e: Exception) {
            LogManager.e(TAG, "检测 Root 类型失败", e)
            return RootType.NONE
        }
    }

    /**
     * 获取 Root 版本信息
     * 基于不同的 Root 方案执行相应的版本查询命令
     */
    private fun fetchRootVersion(): String? {
        return try {
            when (rootType) {
                RootType.MAGISK -> {
                    // 不要在 root shell 里再调用 magisk/su，直接 query
                    val magiskV = rawOutputOrEmpty("magisk", "-V").trim()
                    when {
                        magiskV.isNotBlank() -> magiskV.lines().firstOrNull { it.isNotBlank() }
                        else -> rawOutputOrEmpty("su", "-v").trim().lines().firstOrNull { it.isNotBlank() }
                    }
                }
                RootType.KERNEL_SU -> {
                    val ksuV = rawOutputOrEmpty("ksud", "-V").trim()
                    when {
                        ksuV.isNotBlank() -> ksuV.lines().firstOrNull { it.isNotBlank() }
                        else -> rawOutputOrEmpty("su", "-v").trim().lines().firstOrNull { it.isNotBlank() }
                    }
                }
                RootType.APATCH -> {
                    val apV = rawOutputOrEmpty("apd", "-V").trim()
                    when {
                        apV.isNotBlank() -> apV.lines().firstOrNull { it.isNotBlank() }
                        else -> rawOutputOrEmpty("su", "-v").trim().lines().firstOrNull { it.isNotBlank() }
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "获取 Root 版本失败", e)
            null
        }
    }

    /**
     * 请求 root 权限，返回是否成功
     * 会检测 Root 类型并记录版本信息
     * 使用全局命名空间方式，支持访问私有目录
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            LogManager.d(TAG, "请求 Root 权限")
            
            // 尝试执行 su -c id 来获取权限
            // 使用全局命名空间方式，支持 KernelSU 等方案访问私有目录
            val result = executeSuCommand("id")
            val output = result.first
            val error = result.second
            val exitCode = result.third
            
            LogManager.d(TAG, "su -c id 输出: $output")
            LogManager.d(TAG, "su -c id 错误: $error")
            LogManager.d(TAG, "su -c id 退出码: $exitCode")
            
            val granted = output.contains("uid=0") || exitCode == 0
            isGranted = granted

            if (granted) {
                // 检测 Root 类型
                rootType = detectRootType()
                
                // 获取版本信息
                rootVersion = fetchRootVersion()
                
                LogManager.i(TAG, "Root 权限授权成功 - 类型: ${rootType.name}, 版本: $rootVersion")

                // 验证 su -c whoami
                try {
                    val whoamiResult = executeSuCommandSilent("whoami")
                    val whoami = whoamiResult.trim()
                    LogManager.d(TAG, "su -c whoami = $whoami")
                } catch (e: Exception) {
                    LogManager.w(TAG, "su -c whoami 执行失败", e)
                }
            } else {
                LogManager.w(TAG, "Root 权限授权失败")
                rootType = RootType.NONE
            }
            granted
        } catch (e: Exception) {
            LogManager.e(TAG, "Root 权限请求异常", e)
            rootType = RootType.NONE
            isGranted = false
            false
        }
    }

    /**
     * 以 root 身份执行 shell 命令，返回 stdout 行列表
     * 使用全局命名空间方式，支持访问私有目录
     * @throws Exception 若 root 未授权或命令失败
     */
    /**
     * 内部执行 su 命令的公共逻辑，确保已授权后执行并返回输出行。
     * @param throwOnFail true=失败时抛异常，false=失败时返回 null
     */
    private suspend fun runSuCommand(
        throwOnFail: Boolean,
        vararg cmds: String,
    ): List<String>? {
        if (!isGranted) {
            val granted = requestRoot()
            if (!granted) {
                if (throwOnFail) throw RuntimeException("Root 权限未授权")
                else { LogManager.w(TAG, "Root 权限未授权"); return null }
            }
        }
        val (output, error, exitCode) = executeSuCommand(*cmds)
        LogManager.d(TAG, "命令输出: $output")
        LogManager.d(TAG, "命令错误: $error")
        LogManager.d(TAG, "退出码: $exitCode")
        if (exitCode != 0 && output.isEmpty()) {
            val msg = error.ifEmpty { "未知错误" }
            if (throwOnFail) { LogManager.e(TAG, "Root 命令失败: $msg"); throw RuntimeException("Root 命令失败: $msg") }
            else { LogManager.w(TAG, "Root 命令失败: $msg"); return null }
        }
        return output.lines().filter { it.isNotBlank() }
    }

    /**
     * 以 root 身份执行 shell 命令，返回 stdout 行列表，失败抛异常
     */
    suspend fun exec(vararg cmds: String): List<String> = withContext(Dispatchers.IO) {
        LogManager.d(TAG, "执行 Root 命令: ${cmds.joinToString(" ")}")
        runSuCommand(throwOnFail = true, *cmds) ?: emptyList()
    }

    /**
     * 以 root 身份执行 shell 命令（静默模式），失败返回空列表
     */
    suspend fun execSilent(vararg cmds: String): List<String> = withContext(Dispatchers.IO) {
        try {
            LogManager.d(TAG, "执行 Root 命令 (静默): ${cmds.joinToString(" ")}")
            runSuCommand(throwOnFail = false, *cmds) ?: emptyList()
        } catch (e: Exception) {
            LogManager.e(TAG, "Root 命令异常", e)
            emptyList()
        }
    }

    /**
     * 通过 stdin 管道执行 shell 脚本（不走 -c 参数）。
     *
     * 与 exec("sh -c '...script...'") 相比，stdin 方案无需对脚本内容做任何引号转义，
     * 彻底避免含空格、单引号、Unicode 等特殊字符的路径在 -c 参数中被外层 shell 错误分词。
     *
     * 适用场景：需要批量操作大量可能含特殊字符路径的脚本（如批量 .git 检测）。
     */
    suspend fun execShellScript(script: String): List<String> = withContext(Dispatchers.IO) {
        if (!isGranted) {
            val granted = requestRoot()
            if (!granted) throw RuntimeException("Root 权限未授权")
        }
        ensureSuExecMode()

        // 启动 shell 但不用 -c（脚本通过 stdin 传入，无需任何引号转义）
        val suArgs: Array<String> = when (suExecMode) {
            SuExecMode.NSENTER -> arrayOf("su", "-c", "nsenter --mount=/proc/1/ns/mnt sh")
            SuExecMode.MOUNT_MASTER -> arrayOf("su", "--mount-master", "sh")
            else -> arrayOf("su", "sh")
        }

        LogManager.d(TAG, "execShellScript via stdin, mode=$suExecMode, scriptLen=${script.length}")

        val process = Runtime.getRuntime().exec(suArgs)
        try {
            // 写入脚本后关闭 stdin，shell 收到 EOF 才开始执行并退出
            process.outputStream.bufferedWriter().use { it.write(script) }
            val output = process.inputStream.bufferedReader().readText()
            val error  = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            LogManager.d(TAG, "execShellScript output: $output")
            if (error.isNotBlank()) LogManager.d(TAG, "execShellScript stderr: $error")
            if (exitCode != 0 && output.isEmpty()) {
                throw RuntimeException(error.ifEmpty { "脚本执行失败 (exit $exitCode)" })
            }
            output.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
    }

    /**
     * 以普通用户身份执行 shell 命令
     */
    suspend fun execNormal(vararg cmds: String): List<String> = withContext(Dispatchers.IO) {
        val cmdString = cmds.joinToString(" ")
        LogManager.d(TAG, "执行普通命令: $cmdString")
        
        try {
            val process = Runtime.getRuntime().exec(cmds)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            LogManager.d(TAG, "普通命令输出: $output")
            LogManager.d(TAG, "普通命令错误: $error")
            LogManager.d(TAG, "普通命令退出码: $exitCode")
            
            output.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            LogManager.e(TAG, "普通命令执行异常", e)
            emptyList()
        }
    }

    /**
     * 检查是否有权限访问指定路径
     * 对于需要 root 权限的路径，会尝试使用 root 访问
     */
    suspend fun canAccessPath(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!isGranted) {
            return@withContext try {
                java.io.File(path).canRead()
            } catch (_: Exception) {
                false
            }
        }

        try {
            val result = execSilent("test", "-r", path, "&&", "echo", "1", "||", "echo", "0")
            result.firstOrNull()?.trim() == "1"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 列出目录内容（使用 root 权限）
     * 支持访问普通权限无法访问的目录，包括私有目录
     * 使用全局命名空间方式，兼容 KernelSU、APatch 等方案
     */
    suspend fun listDirWithRoot(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val cmd = arrayOf("ls", "-1", path)
            LogManager.d(TAG, "列出目录: ${cmd.joinToString(" ")}")
            
            // 确保已获得 Root 权限
            if (!isGranted) {
                val granted = requestRoot()
                if (!granted) {
                    LogManager.w(TAG, "Root 权限未授权")
                    return@withContext emptyList()
                }
            }
            
            // 执行命令，使用全局命名空间方式
            val (output, error, exitCode) = executeSuCommand(*cmd)
            
            LogManager.d(TAG, "目录列表输出: $output")
            LogManager.d(TAG, "目录列表错误: $error")
            LogManager.d(TAG, "目录列表退出码: $exitCode")
            
            if (exitCode != 0 && output.isEmpty()) {
                LogManager.w(TAG, "列出目录失败: ${error.ifEmpty { "未知错误" }}")
                return@withContext emptyList()
            }
            
            output.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            LogManager.e(TAG, "listDirWithRoot 异常", e)
            emptyList()
        }
    }

    /**
     * 执行 su 命令（带详细日志）
     * 使用全局命名空间方式，支持访问私有目录
     * 参考 MiXplorer、Neo-Backup 等成熟应用的实现方式
     * @param args 命令参数
     * @return 三元组：(stdout, stderr, exitCode)
     */
    private fun executeSuCommand(vararg args: String): Triple<String, String, Int> {
        ensureSuExecMode()
        return executeSuCommandInternal(suExecMode, *args)
    }

    /**
     * 按指定模式真正去拼接并执行 su 命令。
     * 这里统一处理 nsenter / --mount-master / 普通 su，确保始终在同一个"全局"环境下运行。
     */
    private fun executeSuCommandInternal(
        mode: SuExecMode,
        vararg args: String
    ): Triple<String, String, Int> {
        val plainCmd = args.joinToString(" ")

        // 为内层单引号做转义：' -> '\''（标准 sh 写法）
        fun escapeForSingleQuotes(s: String): String =
            s.replace("'", "'\"'\"'")

        val suArgs: Array<String> = when (mode) {
            SuExecMode.NSENTER -> {
                // su -c 'nsenter --mount=/proc/1/ns/mnt sh -c '<cmd>''
                val inner = "nsenter --mount=/proc/1/ns/mnt sh -c '${escapeForSingleQuotes(plainCmd)}'"
                arrayOf("su", "-c", inner)
            }
            SuExecMode.MOUNT_MASTER -> {
                // su --mount-master -c "<cmd>"
                arrayOf("su", "--mount-master", "-c", plainCmd)
            }
            SuExecMode.PLAIN, SuExecMode.UNKNOWN -> {
                // 回退：普通 su -c
                arrayOf("su", "-c", plainCmd)
            }
        }

        LogManager.d(TAG, "执行 su 命令: ${suArgs.joinToString(" ")}")

        try {
            val process = Runtime.getRuntime().exec(suArgs)
            
            // 读取 stdout
            val output = process.inputStream.bufferedReader().use { it.readText() }
            // 读取 stderr
            val error = process.errorStream.bufferedReader().use { it.readText() }
            // 等待进程结束
            val exitCode = process.waitFor()
            
            return Triple(output, error, exitCode)
        } catch (e: Exception) {
            LogManager.e(TAG, "执行 su 命令异常", e)
            return Triple("", e.message ?: "执行异常", -1)
        }
    }

    /**
     * 静默执行 su 命令（不抛异常）
     * 使用全局命名空间方式，支持访问私有目录
     * @param args 命令参数
     * @return 命令输出，失败返回空字符串
     */
    private fun executeSuCommandSilent(vararg args: String): String {
        return try {
            val (output, _, exitCode) = executeSuCommand(*args)
            if (exitCode == 0) output else ""
        } catch (e: Exception) {
            LogManager.e(TAG, "静默执行 su 命令异常", e)
            ""
        }
    }

    /**
     * 获取 Root 状态描述
     */
    fun getRootStatusDesc(): String {
        return when (rootType) {
            RootType.NONE -> "未获取 Root 权限"
            RootType.MAGISK -> "Magisk ${rootVersion ?: ""}".trim()
            RootType.KERNEL_SU -> "KernelSU ${rootVersion ?: ""}".trim()
            RootType.APATCH -> "APatch ${rootVersion ?: ""}".trim()
            RootType.UNKNOWN -> "Root 已授权 (${rootVersion ?: "未知版本"})"
        }
    }
}
