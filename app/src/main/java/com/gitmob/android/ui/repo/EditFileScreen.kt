package com.gitmob.android.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitmob.android.ui.theme.BlueColor
import com.gitmob.android.ui.theme.Coral
import com.gitmob.android.ui.theme.Green
import com.gitmob.android.ui.theme.LocalGmColors
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.launch

/**
 * 编辑/新建文件的模式
 */
enum class EditFileMode {
    EDIT,
    NEW
}

/**
 * 文件编辑/新建页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFileScreen(
    mode: EditFileMode,
    fileName: String,
    initialContent: String,
    isSymlink: Boolean = false,
    isSubmodule: Boolean = false,
    submoduleGitUrl: String? = null,
    parentPath: String = "",
    onBack: () -> Unit,
    onSave: (fileName: String, content: String, submoduleUrl: String?) -> Unit,
    onFetchLatestSha: ((String, (String?) -> Unit) -> Unit)? = null
) {
    val c = LocalGmColors.current
    val scope = rememberCoroutineScope()
    
    var currentFileName by remember {
        mutableStateOf(
            if (mode == EditFileMode.NEW) {
                if (parentPath.isNotBlank()) {
                    "$parentPath/"
                } else {
                    ""
                }
            } else {
                if (parentPath.isNotBlank()) {
                    "$parentPath/$fileName"
                } else {
                    fileName
                }
            }
        )
    }
    // currentContent 仅用于 Markdown 预览渲染，不再在每次击键时更新。
    // 代码编辑器内容通过 editorRef 在保存时一次性读取，彻底避免重组风暴。
    var currentContent by remember { mutableStateOf(initialContent) }
    var currentSubmoduleUrl by remember { mutableStateOf(submoduleGitUrl ?: "") }
    var showPreview by remember { mutableStateOf(false) }
    var isFetchingSha by remember { mutableStateOf(false) }

    // Sora Editor 实例引用，保存时通过 editorRef.value?.text?.toString() 读取最终内容
    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    // 轻量标志位：只要用户在代码编辑器中触发过任意变更即为 true，不做字符串比较
    var hasContentChanged by remember { mutableStateOf(false) }

    val originalFullPath = remember {
        if (parentPath.isNotBlank()) {
            "$parentPath/$fileName"
        } else {
            fileName
        }
    }
    
    val hasChanges = remember(currentFileName, hasContentChanged, mode) {
        when (mode) {
            EditFileMode.EDIT -> currentFileName != originalFullPath || hasContentChanged
            EditFileMode.NEW -> currentFileName.isNotBlank()
        }
    }
    
    val isMd = remember(currentFileName) {
        !isSymlink && !isSubmodule && currentFileName.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }
    }
    
    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isSymlink -> "编辑符号链接"
                            isSubmodule -> "编辑子模块"
                            mode == EditFileMode.EDIT -> currentFileName
                            else -> "新建文件"
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = c.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = c.bgDeep,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Close, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("取消", color = c.textSecondary)
                        }
                    }
                    
                    if (isMd && !isSymlink && !isSubmodule) {
                        TextButton(onClick = {
                            // 切换到预览模式前，先将编辑器当前内容同步到 currentContent
                            // 使 GmMarkdownWebView 能渲染用户最新的编辑结果
                            if (!showPreview) {
                                editorRef.value?.text?.toString()?.let { currentContent = it }
                            }
                            showPreview = !showPreview
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Visibility, null, tint = if (showPreview) Coral else c.textSecondary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("预览", color = if (showPreview) Coral else c.textSecondary)
                            }
                        }
                    }
                    
                    Button(
                        onClick = {
                            if (currentFileName.isNotBlank()) {
                                val submoduleUrlToSave = if (isSubmodule && mode == EditFileMode.NEW) {
                                    currentSubmoduleUrl.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                }
                                // symlink / submodule 分支直接用 currentContent（OutlinedTextField 绑定）
                                // 普通代码编辑分支从 editorRef 读取，避免在每次击键时更新状态
                                val contentToSave = if (!isSymlink && !isSubmodule) {
                                    editorRef.value?.text?.toString() ?: currentContent
                                } else {
                                    currentContent
                                }
                                onSave(currentFileName, contentToSave, submoduleUrlToSave)
                            }
                        },
                        enabled = hasChanges && currentFileName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Coral,
                            disabledContainerColor = Coral.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("提交更改", color = Color.White)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        when {
            showPreview && isMd && !isSymlink && !isSubmodule -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .imePadding()
                ) {
                    com.gitmob.android.ui.common.GmMarkdownWebView(
                        markdown = currentContent,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    )
                }
            }
            isSymlink -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(BlueColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Link,
                            null,
                            tint = BlueColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text("符号链接", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                            Text("编辑目标路径", fontSize = 12.sp, color = c.textTertiary)
                        }
                    }
                    
                    OutlinedTextField(
                        value = currentFileName,
                        onValueChange = { currentFileName = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        label = { Text("文件路径") },
                        placeholder = { Text("请输入完整路径，例如：docs/README.md", color = c.textTertiary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Coral,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary,
                            unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = Coral,
                            unfocusedLabelColor = c.textTertiary,
                        ),
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text("目标路径：", fontSize = 14.sp, color = c.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
                    
                    OutlinedTextField(
                        value = currentContent,
                        onValueChange = { currentContent = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        label = { Text("请输入目标路径") },
                        placeholder = { Text("例如：docs/README.md", color = c.textTertiary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BlueColor,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary,
                            unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = BlueColor,
                            unfocusedLabelColor = c.textTertiary,
                        ),
                    )
                }
            }
            isSubmodule -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(Green.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.AccountTree,
                            null,
                            tint = Green,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                if (mode == EditFileMode.NEW) "创建 Git 子模块" else "编辑 Git 子模块",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = c.textPrimary
                            )
                            Text(
                                "填写仓库地址，点击按钮自动获取最新 Commit SHA",
                                fontSize = 12.sp,
                                color = c.textTertiary
                            )
                        }
                    }
                    
                    OutlinedTextField(
                        value = currentFileName,
                        onValueChange = { currentFileName = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        label = { Text("子模块路径") },
                        placeholder = { Text("例如：libs/okhttp", color = c.textTertiary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Coral,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary,
                            unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = Coral,
                            unfocusedLabelColor = c.textTertiary,
                        ),
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    Text("仓库地址：", fontSize = 14.sp, color = c.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
                    
                    OutlinedTextField(
                        value = currentSubmoduleUrl,
                        onValueChange = { currentSubmoduleUrl = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        label = { Text("请输入仓库 URL") },
                        placeholder = { Text("例如：https://github.com/square/okhttp.git", color = c.textTertiary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Green,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary,
                            unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = Green,
                            unfocusedLabelColor = c.textTertiary,
                        ),
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (currentSubmoduleUrl.isNotBlank()) {
                                    isFetchingSha = true
                                    onFetchLatestSha?.invoke(
                                        currentSubmoduleUrl,
                                        { sha ->
                                            sha?.let { currentContent = it }
                                            isFetchingSha = false
                                        }
                                    )
                                }
                            },
                            enabled = currentSubmoduleUrl.isNotBlank() && !isFetchingSha,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                        ) {
                            if (isFetchingSha) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = c.textPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("获取中...")
                            } else {
                                Icon(Icons.Default.Autorenew, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("获取最新 Commit SHA")
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    Text("Commit SHA：", fontSize = 14.sp, color = c.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
                    
                    OutlinedTextField(
                        value = currentContent,
                        onValueChange = { currentContent = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        label = { Text("请输入 Commit SHA") },
                        placeholder = { Text("例如：a1b2c3d4e5f6...", color = c.textTertiary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Green,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = Coral,
                            unfocusedTextColor = Coral,
                            focusedContainerColor = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = Green,
                            unfocusedLabelColor = c.textTertiary,
                        ),
                    )
                }
            }
            else -> {
                // else 分支使用 Column 而非 verticalScroll，由 CodeEditorView 内部处理滚动
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .imePadding()
                ) {
                    OutlinedTextField(
                        value = currentFileName,
                        onValueChange = { currentFileName = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        label = { Text("文件路径") },
                        placeholder = { Text("请输入完整路径，例如：docs/README.md", color = c.textTertiary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Coral,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary,
                            unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = Coral,
                            unfocusedLabelColor = c.textTertiary,
                        ),
                    )

                    // Sora Editor 替代 BasicTextField + verticalScroll 方案
                    // weight(1f) 确保编辑器占满剩余空间，不与 OutlinedTextField 争高度
                    com.gitmob.android.ui.common.CodeEditorView(
                        initialContent = initialContent,
                        isDarkTheme = c.isDark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                        onEditorReady = { editor ->
                            editorRef.value = editor
                        },
                        onContentChanged = {
                            hasContentChanged = true
                        }
                    )
                }
            }
        }
    }
}
