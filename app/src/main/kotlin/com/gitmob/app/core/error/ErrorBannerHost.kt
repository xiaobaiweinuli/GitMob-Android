package com.gitmob.app.core.error

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 全局唯一的顶部错误提示条，挂在 Nav 根节点。
 * 不在每个 Screen 里重复实现错误提示 UI。
 */
@Composable
fun ErrorBannerHost(errorEventBus: ErrorEventBus, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<ApiError?>(null) }

    LaunchedEffect(Unit) {
        errorEventBus.events.collect { error ->
            current = error
            delay(3000)
            current = null
        }
    }

    AnimatedVisibility(
        visible = current != null,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier.fillMaxWidth(),
    ) {
        current?.let { error ->
            val containerColor = error.bannerContainerColor()
            val contentColor = error.bannerContentColor()
            Surface(
                color = containerColor,
                modifier = Modifier.padding(8.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(error.icon(), contentDescription = null, tint = contentColor)
                    Spacer(Modifier.width(8.dp))
                    Text(error.displayMessage(), color = contentColor)
                }
            }
        }
    }
}

fun ApiError.displayMessage(): String = when (this) {
    ApiError.Unauthorized -> "登录已失效，请重新登录"
    ApiError.Forbidden -> "权限不足，无法执行此操作"
    ApiError.RateLimited -> "请求过于频繁，请稍后再试"
    ApiError.NetworkError -> "网络异常，请检查网络连接"
    is ApiError.GraphQLError -> errors.firstOrNull()?.message ?: "请求出错"
    is ApiError.Unknown -> message
}

private fun ApiError.icon(): ImageVector = when (this) {
    ApiError.Unauthorized -> Icons.Default.Lock
    ApiError.Forbidden -> Icons.Default.Block
    ApiError.RateLimited -> Icons.Default.Timer
    ApiError.NetworkError -> Icons.Default.WifiOff
    else -> Icons.Default.Error
}

@Composable
private fun ApiError.bannerContainerColor(): Color = when (this) {
    ApiError.NetworkError, ApiError.RateLimited -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun ApiError.bannerContentColor(): Color = when (this) {
    ApiError.NetworkError, ApiError.RateLimited -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onErrorContainer
}
