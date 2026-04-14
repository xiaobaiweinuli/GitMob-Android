package com.gitmob.android.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gitmob.android.ui.common.GmMarkdownWebView
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.UpdateManager

/**
 * 更新日志弹窗
 */
@Composable
fun UpdateDialog(
    release: UpdateManager.Release,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    onUpdate: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val c = LocalGmColors.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.bgCard)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "发现新版本",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary
                        )
                        Text(
                            release.tagName,
                            fontSize = 14.sp,
                            color = Coral
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = c.textSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                HorizontalDivider(color = c.border, thickness = 0.5.dp)

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "更新日志",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    GmMarkdownWebView(
                        markdown = release.body,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onIgnore,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "忽略此版本",
                            color = c.textSecondary,
                            fontSize = 15.sp
                        )
                    }
                    Button(
                        onClick = onUpdate,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Coral)
                    ) {
                        Text(
                            "立即更新",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
