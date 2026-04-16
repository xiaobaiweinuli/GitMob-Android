package com.gitmob.android.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gitmob.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalGmColors.current
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = c.textTertiary)
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, null, tint = c.textSecondary)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
    )
}

@Composable
fun GmCard(
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalGmColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(radius)),
        content = content,
    )
}

@Composable
fun GmBadge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = fg,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun AvatarImage(url: String?, size: Int) {
    val c = LocalGmColors.current
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(c.bgItem),
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Coral, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
    }
}

@Composable
fun ErrorBox(msg: String, onRetry: () -> Unit) {
    val c = LocalGmColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("出错了", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(msg, fontSize = 13.sp, color = c.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Coral)) {
            Text("重试")
        }
    }
}

@Composable
fun EmptyBox(msg: String) {
    val c = LocalGmColors.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, fontSize = 14.sp, color = c.textTertiary, textAlign = TextAlign.Center)
    }
}

@Composable
fun GmDivider() {
    val c = LocalGmColors.current
    HorizontalDivider(color = c.border, thickness = 0.5.dp)
}

/**
 * 可折叠文本组件
 * 超过指定行数时自动折叠，点击切换展开/折叠
 */
@Composable
fun CollapsibleText(
    text: String,
    maxLines: Int = 2,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 20.sp,
    color: androidx.compose.ui.graphics.Color = LocalGmColors.current.textSecondary,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var needCollapse by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    
    Box(
        modifier = Modifier
            .animateContentSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { if (needCollapse) isExpanded = !isExpanded }
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            maxLines = if (isExpanded) Int.MAX_VALUE else maxLines,
            overflow = if (isExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
            onTextLayout = { textLayoutResult ->
                if (textLayoutResult.didOverflowHeight && !isExpanded) {
                    needCollapse = true
                }
            }
        )
    }
}
