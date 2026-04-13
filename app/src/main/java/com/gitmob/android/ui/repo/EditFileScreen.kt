package com.gitmob.android.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitmob.android.ui.theme.Coral
import com.gitmob.android.ui.theme.LocalGmColors

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
    onBack: () -> Unit,
    onSave: (fileName: String, content: String) -> Unit
) {
    val c = LocalGmColors.current
    
    var currentFileName by remember { 
        mutableStateOf(
            if (mode == EditFileMode.NEW) "" else fileName
        ) 
    }
    var currentContent by remember { mutableStateOf(initialContent) }
    var showPreview by remember { mutableStateOf(false) }
    
    val hasChanges = remember(currentFileName, currentContent, mode) {
        when (mode) {
            EditFileMode.EDIT -> currentFileName != fileName || currentContent != initialContent
            EditFileMode.NEW -> currentFileName.isNotBlank()
        }
    }
    
    val isMd = remember(currentFileName) {
        currentFileName.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }
    }
    
    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (mode) {
                            EditFileMode.EDIT -> currentFileName
                            EditFileMode.NEW -> "新建文件"
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
                    
                    if (isMd) {
                        TextButton(onClick = { showPreview = !showPreview }) {
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
                                onSave(currentFileName, currentContent)
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
        if (showPreview && isMd) {
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = currentFileName,
                    onValueChange = { currentFileName = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("文件名") },
                    placeholder = { Text("请输入文件名", color = c.textTertiary) },
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
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                        .background(c.bgItem, RoundedCornerShape(12.dp))
                ) {
                    BasicTextField(
                        value = currentContent,
                        onValueChange = { currentContent = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        textStyle = TextStyle(
                            color = c.textPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        cursorBrush = SolidColor(Coral)
                    ) { innerTextField ->
                        if (currentContent.isEmpty()) {
                            Text(
                                text = "// 文件内容",
                                color = c.textTertiary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        innerTextField()
                    }
                }
            }
        }
    }
}
