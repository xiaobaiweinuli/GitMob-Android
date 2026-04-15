package com.gitmob.android.ui.filepicker

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitmob.android.auth.RootManager
import com.gitmob.android.ui.common.GmDivider
import com.gitmob.android.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

// ─── 数据类 ───────────────────────────────────────────────────────────────────

data class FileEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val isGitRepo: Boolean = false,
    val size: Long = 0L,
    val lastModified: Long = 0L,
)

/**
 * 书签：iconKey 使用 String 而非 ImageVector，Gson 可以正确序列化/反序列化。
 */
data class BookmarkPath(
    @com.google.gson.annotations.SerializedName("label")        val label: String,
    @com.google.gson.annotations.SerializedName("path")         val path: String,
    @com.google.gson.annotations.SerializedName("iconKey")      val iconKey: String = "Folder",
    @com.google.gson.annotations.SerializedName("requiresRoot") val requiresRoot: Boolean = false,
    @com.google.gson.annotations.SerializedName("isCustom")     val isCustom: Boolean = false,
)

/** 根据 iconKey 解析出实际图标（空值/未知 key 均返回 Folder 兜底，防止 NPE） */
fun BookmarkPath.resolveIcon(): ImageVector = when (iconKey.orEmpty()) {
    "SdCard"         -> Icons.Default.SdCard
    "Download"       -> Icons.Default.Download
    "Terminal"       -> Icons.Default.Terminal
    "Storage"        -> Icons.Default.Storage
    "Memory"         -> Icons.Default.Memory
    "Settings"       -> Icons.Default.Settings
    "BookmarkAdded"  -> Icons.Default.BookmarkAdded
    else             -> Icons.Default.Folder
}

private fun defaultBookmarks(rootEnabled: Boolean): List<BookmarkPath> = emptyList()

enum class PickerMode { DIRECTORY, MULTI_FILE, SINGLE_FILE }
enum class SortType { NAME, DATE, SIZE, TYPE }
enum class SortOrder { ASCENDING, DESCENDING }

// ─── 工具 ─────────────────────────────────────────────────────────────────────

private fun isPrivilegedPath(path: String) =
    path.startsWith("/data") || path.startsWith("/system") ||
    path == "/"              || path.startsWith("/proc")   || path.startsWith("/dev")

private fun formatSize(bytes: Long) = when {
    bytes < 1024L         -> "${bytes}B"
    bytes < 1024L * 1024  -> "${bytes / 1024}KB"
    else                  -> "${bytes / 1024 / 1024}MB"
}

/** root 列目录：`ls -1Ap` 目录名带 `/` 后缀 */
private suspend fun listViaRoot(path: String, detectGitRepos: Boolean = true): List<FileEntry> {
    // 单引号包裹路径，转义内嵌单引号，防含空格/特殊字符路径出错
    val escapedPath = path.replace("'", "'\\''")
    val lines = RootManager.exec("ls -1Ap --color=never '$escapedPath' 2>/dev/null")
    val entries = lines.filter { it.isNotBlank() }.mapNotNull { raw ->
        val isDir = raw.endsWith("/")
        val name = raw.trimEnd('/')
        if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
        val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
        FileEntry(path = fullPath, name = name, isDir = isDir, isGitRepo = false, size = 0L, lastModified = 0L)
    }

    if (!detectGitRepos) return entries

    // 用 find 单条命令批量检测 .git，不再逐个 su fork（原来50个目录=50次 fork，慢且易超时）
    val gitDirs: Set<String> = try {
        val results = RootManager.exec(
            "find '$escapedPath' -mindepth 2 -maxdepth 2 -name '.git' -type d 2>/dev/null"
        )
        results.map { it.removeSuffix("/.git") }.toSet()
    } catch (_: Exception) { emptySet() }

    return entries.map { it.copy(isGitRepo = it.isDir && it.path in gitDirs) }
}

/** 普通权限列目录 */
private fun listNormal(path: String): List<FileEntry> {
    val files = File(path).listFiles() ?: return emptyList()
    return files
        .map { f ->
            FileEntry(
                path = f.absolutePath, 
                name = f.name,
                isDir = f.isDirectory,
                isGitRepo = f.isDirectory && File(f, ".git").exists(),
                size = if (!f.isDirectory) f.length() else 0L,
                lastModified = f.lastModified()
            )
        }
}

/** 检查文件扩展名是否允许 */
private fun isExtensionAllowed(entry: FileEntry, allowedExtensions: List<String>?): Boolean {
    if (entry.isDir) return true
    if (allowedExtensions == null) return true
    val nameLower = entry.name.lowercase()
    return allowedExtensions.any { ext -> nameLower.endsWith(".$ext") }
}

/** 排序文件列表 */
private fun sortFiles(
    files: List<FileEntry>,
    sortType: SortType,
    sortOrder: SortOrder,
    showHidden: Boolean,
    allowedExtensions: List<String>? = null
): List<FileEntry> {
    var filtered = if (showHidden) files else files.filter { !it.name.startsWith(".") }
    allowedExtensions?.let { exts ->
        filtered = filtered.filter { isExtensionAllowed(it, exts) }
    }
    val sorted = when (sortType) {
        SortType.NAME -> filtered.sortedBy { it.name.lowercase() }
        SortType.DATE -> filtered.sortedBy { it.lastModified }
        SortType.SIZE -> filtered.sortedWith(compareBy({ !it.isDir }, { it.size }))
        SortType.TYPE -> filtered.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
    
    return if (sortOrder == SortOrder.DESCENDING) sorted.reversed() else sorted
}

// ─── 主 Composable ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    title: String = "选择目录",
    mode: PickerMode = PickerMode.DIRECTORY,
    initialPath: String = Environment.getExternalStorageDirectory().absolutePath,
    rootEnabled: Boolean = false,
    detectGitRepos: Boolean = false,          // 仅"导入目录"场景需要，其他场景传 false 跳过检测
    customBookmarks: List<BookmarkPath> = emptyList(),
    onAddBookmark: ((BookmarkPath) -> Unit)? = null,
    onRemoveBookmark: ((BookmarkPath) -> Unit)? = null,
    onEditBookmark: ((BookmarkPath) -> Unit)? = null,
    allowedExtensions: List<String>? = null,   // 允许的文件扩展名（不包含点，如 ["json"]），null 表示无限制
    onConfirm: (selectedDir: String, selectedFiles: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalGmColors.current
    val scope = rememberCoroutineScope()

    var currentPath by rememberSaveable { mutableStateOf(initialPath) }
    var entries    by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading    by remember { mutableStateOf(false) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    var showMoreMenu     by remember { mutableStateOf(false) }
    var newDirDialog     by remember { mutableStateOf(false) }
    var newDirName       by remember { mutableStateOf("") }
    var showBookmarkPanel by remember { mutableStateOf(false) }
    var bookmarkDialog   by remember { mutableStateOf(false) }
    var bookmarkLabel    by remember { mutableStateOf("") }
    var bookmarkEntry    by remember { mutableStateOf<FileEntry?>(null) }
    var renameDialog     by remember { mutableStateOf(false) }
    var renameEntry      by remember { mutableStateOf<FileEntry?>(null) }
    var newName          by remember { mutableStateOf("") }
    var deleteDialog     by remember { mutableStateOf(false) }
    var deleteEntry      by remember { mutableStateOf<FileEntry?>(null) }
    var contextMenuEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showContextMenu  by remember { mutableStateOf(false) }
    var showBookmarkMenu by remember { mutableStateOf(false) }
    var bookmarkMenuEntry by remember { mutableStateOf<BookmarkPath?>(null) }
    var editBookmarkDialog by remember { mutableStateOf(false) }
    var editBookmarkEntry by remember { mutableStateOf<BookmarkPath?>(null) }
    var editBookmarkLabel by remember { mutableStateOf("") }
    var editBookmarkPath by remember { mutableStateOf("") }

    // rememberSaveable 跨 tab 切换保留排序偏好；enum 用 ordinal(Int) 桥接（Int 原生可序列化）
    var sortTypeOrdinal  by rememberSaveable { mutableIntStateOf(SortType.TYPE.ordinal) }
    var sortOrderOrdinal by rememberSaveable { mutableIntStateOf(SortOrder.ASCENDING.ordinal) }
    val sortType  = SortType.entries[sortTypeOrdinal]
    val sortOrder = SortOrder.entries[sortOrderOrdinal]
    var showHidden by rememberSaveable { mutableStateOf(false) }

    val isPrivileged = remember(currentPath) { isPrivilegedPath(currentPath) }
    val accentColor  = if (isPrivileged && rootEnabled) Color(0xFFFF6B00) else Coral

    // 高对比文字颜色：Root 深棕背景→亮色；普通→主题色
    val onBg          : Color = if (isPrivileged && rootEnabled) Color(0xFFFFE0B0) else c.textPrimary
    val onBgSecondary : Color = if (isPrivileged && rootEnabled) Color(0xFFCCA870) else c.textSecondary
    val onBgTertiary  : Color = if (isPrivileged && rootEnabled) Color(0xFF997050) else c.textTertiary

    val allBookmarks = remember(customBookmarks) {
        customBookmarks
    }

    fun loadDir(path: String) {
        scope.launch {
            loading = true; errorMsg = null
            try {
                val needsRoot  = isPrivilegedPath(path)
                val forceRoot  = rootEnabled && RootManager.isGranted
                val result = when {
                    forceRoot -> listViaRoot(path, detectGitRepos)
                    forceRoot && !RootManager.isGranted -> {
                        if (RootManager.requestRoot()) listViaRoot(path, detectGitRepos) else listNormal(path)
                    }
                    needsRoot && !rootEnabled ->
                        throw RuntimeException("此目录需要 Root 权限。\n请在设置中启用 Root 模式。")
                    else -> {
                        val e = listNormal(path)
                        if (e.isEmpty() && path.contains("/storage/emulated") && !rootEnabled)
                            throw RuntimeException(
                                "无法读取此目录（Android 11+ 存储限制）。\n" +
                                "请授予「所有文件访问权限」，或开启 Root 模式。"
                            )
                        e
                    }
                }
                entries = sortFiles(result, sortType, sortOrder, showHidden, allowedExtensions); currentPath = path
            } catch (e: Exception) { errorMsg = e.message }
            finally { loading = false }
        }
    }

    /** 重命名文件或目录 */
    fun renameFile(entry: FileEntry, newName: String) {
        scope.launch {
            try {
                val parentPath = File(entry.path).parent ?: return@launch
                val newPath = "$parentPath/$newName"
                if (isPrivileged && rootEnabled && RootManager.isGranted) {
                    run {
                    val s = entry.path.replace("'", "'\''"  )
                    val d = newPath.replace("'", "'\''"  )
                    RootManager.exec("mv '$s' '$d'")
                }
                } else {
                    File(entry.path).renameTo(File(newPath))
                }
                loadDir(currentPath)
            } catch (e: Exception) {
                errorMsg = "重命名失败：${e.message}"
            }
        }
    }

    /** 删除文件或目录 */
    fun deleteFile(entry: FileEntry) {
        scope.launch {
            try {
                if (isPrivileged && rootEnabled && RootManager.isGranted) {
                    if (entry.isDir) {
                        run {
                        val p = entry.path.replace("'", "'\''"  )
                        RootManager.exec("rm -rf '$p'")
                    }
                    } else {
                        run {
                        val p = entry.path.replace("'", "'\''"  )
                        RootManager.exec("rm '$p'")
                    }
                    }
                } else {
                    if (entry.isDir) {
                        File(entry.path).deleteRecursively()
                    } else {
                        File(entry.path).delete()
                    }
                }
                loadDir(currentPath)
            } catch (e: Exception) {
                errorMsg = "删除失败：${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { loadDir(currentPath) }

    // 排序条件变化时只对已有列表重新排序，不重新读盘（原来重新 loadDir 会触发双重 loading）
    LaunchedEffect(sortType, sortOrder, showHidden) {
        if (entries.isNotEmpty()) {
            entries = sortFiles(entries, sortType, sortOrder, showHidden, allowedExtensions)
        }
    }

    val bgTint = if (isPrivileged && rootEnabled) Color(0xFF1A1000) else c.bgDeep

    // 普通模式的存储根目录（返回到此再按返回才关闭）
    val storageRoot = remember {
        android.os.Environment.getExternalStorageDirectory().absolutePath  // /storage/emulated/0
    }

    // 拦截返回键
    BackHandler {
        val parentPath = File(currentPath).parent
        when {
            // 已在顶层（根目录或无父目录）→ 关闭
            parentPath == null || parentPath == currentPath -> onDismiss()

            // root 模式：可以一路返回到根目录 "/"，到根目录后再按才关闭
            rootEnabled && RootManager.isGranted -> {
                loadDir(parentPath)
            }

            // 普通模式：父目录是特权路径（/data /system 等）→ 不可访问，关闭
            isPrivilegedPath(parentPath) -> onDismiss()

            // 普通模式：当前已在存储根目录 → 关闭
            currentPath == storageRoot -> onDismiss()

            // 普通模式：父目录可读 → 返回父目录
            File(parentPath).canRead() -> loadDir(parentPath)

            // 普通模式：父目录不可读（无权限）→ 关闭
            else -> onDismiss()
        }
    }

    // ── 新建目录弹窗 ──────────────────────────────────────────
    if (newDirDialog) {
        AlertDialog(
            onDismissRequest = { newDirDialog = false },
            containerColor = c.bgCard,
            title = { Text("新建文件夹", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newDirName, onValueChange = { newDirName = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("文件夹名称", color = c.textTertiary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor, unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newDirName.isNotBlank()) {
                            scope.launch {
                                val target = "$currentPath/$newDirName"
                                if (isPrivileged && rootEnabled && RootManager.isGranted)
                                    run {
                                    val t = target.replace("'", "'\''"  )
                                    RootManager.exec("mkdir -p '$t'")
                                }
                                else File(target).mkdirs()
                                newDirName = ""; newDirDialog = false; loadDir(currentPath)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { newDirDialog = false }) { Text("取消", color = c.textSecondary) }
            },
        )
    }

    // ── 收藏弹窗 ──────────────────────────────────────────────
    if (bookmarkDialog && bookmarkEntry != null) {
        AlertDialog(
            onDismissRequest = { bookmarkDialog = false; bookmarkEntry = null },
            containerColor = c.bgCard,
            title = { Text("添加书签", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = bookmarkLabel, onValueChange = { bookmarkLabel = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(bookmarkEntry!!.name, color = c.textTertiary) },
                    label = { Text("书签名称") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor, unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                        focusedLabelColor = accentColor, unfocusedLabelColor = c.textTertiary,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lbl = bookmarkLabel.ifBlank { bookmarkEntry!!.name }
                        val bm = BookmarkPath(
                            label = lbl, path = bookmarkEntry!!.path,
                            iconKey = if (bookmarkEntry!!.isDir) "Folder" else "Description",
                            requiresRoot = isPrivilegedPath(bookmarkEntry!!.path), isCustom = true,
                        )
                        onAddBookmark?.invoke(bm)
                        bookmarkLabel = ""; bookmarkDialog = false; bookmarkEntry = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkDialog = false; bookmarkEntry = null }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    // ── 重命名弹窗 ──────────────────────────────────────────────
    if (renameDialog && renameEntry != null) {
        AlertDialog(
            onDismissRequest = { renameDialog = false; renameEntry = null },
            containerColor = c.bgCard,
            title = { Text("重命名", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(renameEntry!!.name, color = c.textTertiary) },
                    label = { Text("新名称") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor, unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                        focusedLabelColor = accentColor, unfocusedLabelColor = c.textTertiary,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            renameFile(renameEntry!!, newName)
                            newName = ""; renameDialog = false; renameEntry = null
                        }
                    },
                    enabled = newName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                ) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialog = false; renameEntry = null }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    // ── 删除确认弹窗 ──────────────────────────────────────────
    if (deleteDialog && deleteEntry != null) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false; deleteEntry = null },
            containerColor = c.bgCard,
            title = { Text("确认删除", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "确定要删除「${deleteEntry!!.name}」吗？${if (deleteEntry!!.isDir) "此操作将递归删除目录下的所有内容。" else ""}",
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteFile(deleteEntry!!)
                        deleteDialog = false; deleteEntry = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedColor),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = false; deleteEntry = null }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    // ── 长按操作菜单弹窗 ──────────────────────────────────────
    if (showContextMenu && contextMenuEntry != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                showContextMenu = false
                contextMenuEntry = null
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        showContextMenu = false
                        contextMenuEntry = null
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(280.dp)
                        .clickable(enabled = false) { },
                    colors = CardDefaults.cardColors(containerColor = c.bgCard),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = contextMenuEntry!!.name,
                            color = c.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 添加书签
                        Button(
                            onClick = {
                                bookmarkEntry = contextMenuEntry
                                bookmarkLabel = contextMenuEntry!!.name
                                showContextMenu = false
                                contextMenuEntry = null
                                bookmarkDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Icon(Icons.Default.BookmarkAdd, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("添加书签")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 重命名
                        Button(
                            onClick = {
                                renameEntry = contextMenuEntry
                                newName = contextMenuEntry!!.name
                                showContextMenu = false
                                contextMenuEntry = null
                                renameDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = c.bgItem)
                        ) {
                            Icon(Icons.Default.Edit, null, tint = c.textPrimary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("重命名", color = c.textPrimary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 删除
                        Button(
                            onClick = {
                                deleteEntry = contextMenuEntry
                                showContextMenu = false
                                contextMenuEntry = null
                                deleteDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = RedColor)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("删除")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 取消
                        TextButton(
                            onClick = {
                                showContextMenu = false
                                contextMenuEntry = null
                            }
                        ) {
                            Text("取消", color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }

    // ── 书签长按菜单弹窗 ──────────────────────────────────────
    if (showBookmarkMenu && bookmarkMenuEntry != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                showBookmarkMenu = false
                bookmarkMenuEntry = null
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        showBookmarkMenu = false
                        bookmarkMenuEntry = null
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(280.dp)
                        .clickable(enabled = false) { },
                    colors = CardDefaults.cardColors(containerColor = c.bgCard),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = bookmarkMenuEntry!!.label,
                            color = c.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (bookmarkMenuEntry!!.isCustom) {
                            // 编辑
                            Button(
                                onClick = {
                                    editBookmarkEntry = bookmarkMenuEntry
                                    editBookmarkLabel = bookmarkMenuEntry!!.label
                                    editBookmarkPath = bookmarkMenuEntry!!.path
                                    showBookmarkMenu = false
                                    bookmarkMenuEntry = null
                                    editBookmarkDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("编辑")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // 删除
                            Button(
                                onClick = {
                                    onRemoveBookmark?.invoke(bookmarkMenuEntry!!)
                                    showBookmarkMenu = false
                                    bookmarkMenuEntry = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = RedColor)
                            ) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("删除")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        // 取消
                        TextButton(
                            onClick = {
                                showBookmarkMenu = false
                                bookmarkMenuEntry = null
                            }
                        ) {
                            Text("取消", color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }

    // ── 编辑书签弹窗 ──────────────────────────────────────────
    if (editBookmarkDialog && editBookmarkEntry != null) {
        AlertDialog(
            onDismissRequest = { editBookmarkDialog = false; editBookmarkEntry = null },
            containerColor = c.bgCard,
            title = { Text("编辑书签", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editBookmarkLabel, onValueChange = { editBookmarkLabel = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("书签名称") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor, unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = accentColor, unfocusedLabelColor = c.textTertiary,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editBookmarkPath, onValueChange = { editBookmarkPath = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("路径") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor, unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = accentColor, unfocusedLabelColor = c.textTertiary,
                        ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = BookmarkPath(
                            label = editBookmarkLabel,
                            path = editBookmarkPath,
                            iconKey = editBookmarkEntry!!.iconKey,
                            requiresRoot = editBookmarkEntry!!.requiresRoot,
                            isCustom = true
                        )
                        onEditBookmark?.invoke(updated)
                        editBookmarkDialog = false; editBookmarkEntry = null
                    },
                    enabled = editBookmarkLabel.isNotBlank() && editBookmarkPath.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editBookmarkDialog = false; editBookmarkEntry = null }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    // ── 更多选项菜单 ──────────────────────────────────────────
    if (showMoreMenu) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showMoreMenu = false },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showMoreMenu = false },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(300.dp)
                        .clickable(enabled = false) { },
                    colors = CardDefaults.cardColors(containerColor = c.bgCard),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "排序方式",
                            color = c.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        // 名称
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val newOrder = if (sortType == SortType.NAME && sortOrder == SortOrder.ASCENDING)
                                    SortOrder.DESCENDING else SortOrder.ASCENDING
                                sortTypeOrdinal = SortType.NAME.ordinal
                                sortOrderOrdinal = newOrder.ordinal
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sortType == SortType.NAME,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("名称", color = c.textPrimary, fontSize = 14.sp)
                            if (sortType == SortType.NAME) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    if (sortOrder == SortOrder.ASCENDING) 
                                        Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        // 日期
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val newOrder = if (sortType == SortType.DATE && sortOrder == SortOrder.ASCENDING)
                                    SortOrder.DESCENDING else SortOrder.ASCENDING
                                sortTypeOrdinal = SortType.DATE.ordinal
                                sortOrderOrdinal = newOrder.ordinal
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sortType == SortType.DATE,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("日期", color = c.textPrimary, fontSize = 14.sp)
                            if (sortType == SortType.DATE) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    if (sortOrder == SortOrder.ASCENDING) 
                                        Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        // 大小
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val newOrder = if (sortType == SortType.SIZE && sortOrder == SortOrder.ASCENDING)
                                    SortOrder.DESCENDING else SortOrder.ASCENDING
                                sortTypeOrdinal = SortType.SIZE.ordinal
                                sortOrderOrdinal = newOrder.ordinal
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sortType == SortType.SIZE,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("大小", color = c.textPrimary, fontSize = 14.sp)
                            if (sortType == SortType.SIZE) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    if (sortOrder == SortOrder.ASCENDING) 
                                        Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        // 类型
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val newOrder = if (sortType == SortType.TYPE && sortOrder == SortOrder.ASCENDING)
                                    SortOrder.DESCENDING else SortOrder.ASCENDING
                                sortTypeOrdinal = SortType.TYPE.ordinal
                                sortOrderOrdinal = newOrder.ordinal
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sortType == SortType.TYPE,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("类型", color = c.textPrimary, fontSize = 14.sp)
                            if (sortType == SortType.TYPE) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    if (sortOrder == SortOrder.ASCENDING) 
                                        Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        GmDivider()
                        
                        // 隐藏文件
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = showHidden,
                                onCheckedChange = { showHidden = it },
                                colors = CheckboxDefaults.colors(checkedColor = accentColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("显示隐藏文件", color = c.textPrimary, fontSize = 14.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = { showMoreMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Text("确定")
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = bgTint,
        topBar = {
            TopAppBar(
                title = {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = onBg)
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = onBgSecondary)
                    }
                },
                actions = {
                    if (isPrivileged && rootEnabled) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF6B00), RoundedCornerShape(50)))
                        Spacer(Modifier.width(6.dp))
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, null, tint = onBgSecondary)
                    }
                    IconButton(onClick = { newDirDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, null, tint = accentColor)
                    }
                    TextButton(
                        onClick = {
                            val files = selected.keys.filter { selected[it] == true }
                            onConfirm(currentPath, files)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = accentColor),
                        enabled = when (mode) {
                            PickerMode.DIRECTORY -> true
                            PickerMode.MULTI_FILE -> selected.isNotEmpty()
                            PickerMode.SINGLE_FILE -> selected.isNotEmpty()
                        }
                    ) {
                        Text(
                            when (mode) {
                                PickerMode.DIRECTORY -> "选择此处"
                                PickerMode.MULTI_FILE -> "确认(${selected.size})"
                                PickerMode.SINGLE_FILE -> "确认"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgTint),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // ── 文件列表 ────────────────────────────────────
            Column(Modifier.weight(1f).fillMaxWidth()) {
                PickerBreadcrumbs(currentPath, accentColor, onBgTertiary, onBg, ::loadDir, c)
                GmDivider()

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accentColor,
                            modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                    errorMsg != null -> Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.Lock, null, tint = onBgTertiary, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("无法访问此目录", fontSize = 14.sp, color = onBg, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(errorMsg!!, fontSize = 11.sp, color = onBgSecondary, lineHeight = 16.sp)
                    }
                    entries.isEmpty() -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        if (currentPath != "/") {
                            item(key = "..") {
                                FileEntryRow(
                                    entry = FileEntry(File(currentPath).parent ?: "/", "..", true),
                                    isSelected = false, showCheckbox = false,
                                    textColor = onBg, textSecondary = onBgSecondary,
                                    textTertiary = onBgTertiary, accentColor = accentColor, c = c,
                                    onClick = { loadDir(File(currentPath).parent ?: "/") },
                                    onLongClick = {},
                                    onCheck = {},
                                )
                            }
                        }
                        item(key = "empty") {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("空目录", fontSize = 13.sp, color = onBgTertiary)
                            }
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        if (currentPath != "/") {
                            item(key = "..") {
                                FileEntryRow(
                                    entry = FileEntry(File(currentPath).parent ?: "/", "..", true),
                                    isSelected = false, showCheckbox = false,
                                    textColor = onBg, textSecondary = onBgSecondary,
                                    textTertiary = onBgTertiary, accentColor = accentColor, c = c,
                                    onClick = { loadDir(File(currentPath).parent ?: "/") },
                                    onLongClick = {},
                                    onCheck = {},
                                )
                            }
                        }
                        items(entries, key = { it.path }) { entry ->
                            FileEntryRow(
                                entry = entry,
                                isSelected = selected[entry.path] == true,
                                showCheckbox = (mode == PickerMode.MULTI_FILE || mode == PickerMode.SINGLE_FILE) && !entry.isDir,
                                textColor = onBg, textSecondary = onBgSecondary,
                                textTertiary = onBgTertiary, accentColor = accentColor, c = c,
                                onClick = {
                                    if (entry.isDir) {
                                        loadDir(entry.path)
                                    } else if (mode == PickerMode.MULTI_FILE) {
                                        selected[entry.path] = !(selected[entry.path] ?: false)
                                    } else if (mode == PickerMode.SINGLE_FILE) {
                                        selected.clear()
                                        selected[entry.path] = true
                                    }
                                },
                                onLongClick = {
                                    contextMenuEntry = entry
                                    showContextMenu = true
                                },
                                onCheck = { checked ->
                                    if (mode == PickerMode.SINGLE_FILE) {
                                        selected.clear()
                                        if (checked) {
                                            selected[entry.path] = true
                                        }
                                    } else {
                                        selected[entry.path] = checked
                                    }
                                },
                            )
                        }
                    }
                }
            }
            
            // ── 底部书签栏 ────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(c.bgCard)
                    .clickable {
                        showBookmarkPanel = !showBookmarkPanel
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(c.border, RoundedCornerShape(2.dp))
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                )
                Text(if (showBookmarkPanel) "点击收起书签" else "点击显示书签", color = c.textTertiary, fontSize = 12.sp)
            }

            // ── 书签面板（显示在文件选择器内部）────────────────────────────
            AnimatedVisibility(
                visible = showBookmarkPanel,
                enter = expandVertically(expandFrom = Alignment.Bottom),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    colors = CardDefaults.cardColors(containerColor = c.bgCard),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "书签",
                            color = c.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (allBookmarks.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无书签", color = c.textTertiary, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(allBookmarks, key = { it.path }) { bm ->
                                    BookmarkItemHorizontal(
                                        bm = bm,
                                        selected = currentPath.startsWith(bm.path),
                                        onClick = {
                                            loadDir(bm.path)
                                            showBookmarkPanel = false
                                        },
                                        onLongClick = {
                                            bookmarkMenuEntry = bm
                                            showBookmarkMenu = true
                                        },
                                        c = c,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── 子组件 ────────────────────────────────────────────────────────────────────

@Composable
private fun BookmarkItem(
    bm: BookmarkPath, selected: Boolean,
    onClick: () -> Unit, onRemove: (() -> Unit)?, c: GmColors,
) {
    val bg   = if (selected) CoralDim else Color.Transparent
    val tint = when {
        selected          -> Coral
        bm.requiresRoot   -> Color(0xFFFF6B00)
        else              -> c.textTertiary
    }
    Box(
        modifier = Modifier.fillMaxWidth().background(bg)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(bm.resolveIcon(), null, tint = tint, modifier = Modifier.size(20.dp))
            Text(bm.label, fontSize = 9.sp, color = tint, maxLines = 1)
            if (bm.isCustom && onRemove != null) {
                Text("×", fontSize = 10.sp, color = RedColor,
                    modifier = Modifier.clickable { onRemove() }.padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun BookmarkItemHorizontal(
    bm: BookmarkPath, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, c: GmColors,
) {
    val bg   = if (selected) CoralDim else Color.Transparent
    val tint = when {
        selected          -> Coral
        bm.requiresRoot   -> Color(0xFFFF6B00)
        else              -> c.textTertiary
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(bm.resolveIcon(), null, tint = tint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(bm.label, fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(bm.path, fontSize = 10.sp, color = c.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun PickerBreadcrumbs(
    path: String, accentColor: Color, tertiaryColor: Color, primaryColor: Color,
    onNavigate: (String) -> Unit, c: GmColors,
) {
    val segments = buildList {
        var p = path
        while (p.isNotEmpty() && p != "/") { add(p); p = File(p).parent ?: break }
        if (!contains("/")) add("/")
    }.reversed()

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(c.bgCard).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { i, seg ->
            val name = if (seg == "/") "/" else File(seg).name
            val isCurrent = seg == path
            Text(
                text = name, fontSize = 12.sp,
                color = if (isCurrent) accentColor else tertiaryColor,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onNavigate(seg) }.padding(horizontal = 3.dp),
            )
            if (i < segments.lastIndex)
                Text("/", fontSize = 11.sp, color = c.border, modifier = Modifier.padding(horizontal = 1.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEntryRow(
    entry: FileEntry, isSelected: Boolean, showCheckbox: Boolean,
    textColor: Color, textSecondary: Color, textTertiary: Color,
    accentColor: Color, c: GmColors,
    onClick: () -> Unit, onLongClick: () -> Unit, onCheck: (Boolean) -> Unit,
) {
    val rowBg = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.Transparent
    Row(
        modifier = Modifier.fillMaxWidth().background(rowBg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val icon = when {
            entry.name == ".." -> Icons.AutoMirrored.Filled.ArrowBack
            entry.isGitRepo    -> Icons.Default.AccountTree
            entry.isDir        -> Icons.Default.Folder
            else               -> Icons.Default.Description
        }
        val iconTint = when {
            entry.isGitRepo -> Green
            entry.isDir     -> Yellow
            else            -> textSecondary
        }
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 13.sp, color = textColor,
                fontWeight = FontWeight.Medium, maxLines = 1)
            when {
                entry.isGitRepo -> Text("Git 仓库", fontSize = 10.sp, color = Green,
                    modifier = Modifier.padding(top = 1.dp))
                !entry.isDir && entry.size > 0 ->
                    Text(formatSize(entry.size), fontSize = 10.sp, color = textTertiary)
                entry.name == ".." -> Text("返回上级", fontSize = 10.sp, color = textTertiary)
            }
        }
        if (entry.isDir && entry.name != "..") {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                tint = textTertiary, modifier = Modifier.size(14.dp))
        }
        if (showCheckbox) {
            Checkbox(
                checked = isSelected, onCheckedChange = onCheck,
                colors = CheckboxDefaults.colors(
                    checkedColor = accentColor, uncheckedColor = c.border),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
