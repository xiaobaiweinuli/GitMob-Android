package com.gitmob.app.core.error

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import kotlinx.coroutines.delay

/**
 * 全局唯一的顶部错误提示条，挂在 Nav 根节点。
 * 不在每个 Screen 里重复实现错误提示 UI。
 */
@Composable
fun ErrorBannerHost(errorEventBus: ErrorEventBus, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<BannerEvent?>(null) }

    LaunchedEffect(Unit) {
        errorEventBus.events.collect { event ->
            current = event
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
        current?.let { event ->
            val containerColor = event.bannerContainerColor()
            val contentColor = event.bannerContentColor()
            Surface(
                color = containerColor,
                modifier = Modifier.padding(8.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(event.icon(), contentDescription = null, tint = contentColor)
                    Spacer(Modifier.width(8.dp))
                    Text(event.displayMessage(), color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun BannerEvent.displayMessage(): String = when (this) {
    is BannerEvent.Error -> error.displayMessage()
    is BannerEvent.Notice -> stringResource(messageRes)
}

private fun BannerEvent.icon(): ImageVector = when (this) {
    is BannerEvent.Error -> error.icon()
    is BannerEvent.Notice -> Icons.Default.CheckCircle
}

@Composable
private fun BannerEvent.bannerContainerColor(): Color = when (this) {
    is BannerEvent.Error -> error.bannerContainerColor()
    is BannerEvent.Notice -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun BannerEvent.bannerContentColor(): Color = when (this) {
    is BannerEvent.Error -> error.bannerContentColor()
    is BannerEvent.Notice -> MaterialTheme.colorScheme.onPrimaryContainer
}

/**
 * ApiError → 文案资源的纯映射（非 Composable，ViewModel 也能用）。
 * GraphQLError 的服务端原文走 [serverMessage] 优先展示；Unknown 一律通用文案，
 * 技术细节只进 Logcat（见 safeCall 的日志）。
 */
@StringRes
fun ApiError.displayMessageRes(): Int = when (this) {
    ApiError.Unauthorized -> R.string.error_unauthorized
    ApiError.Forbidden -> R.string.error_forbidden
    ApiError.RateLimited -> R.string.error_rate_limited
    ApiError.NetworkError -> R.string.error_network
    is ApiError.UserVisible -> messageRes
    is ApiError.GraphQLError -> R.string.error_request_failed
    is ApiError.Unknown -> R.string.error_request_failed
}

/** 服务端返回的原始错误文案（仅 GraphQL 错误有），语言由服务端决定，优先于本地资源展示。 */
fun ApiError.serverMessage(): String? =
    (this as? ApiError.GraphQLError)?.errors?.firstOrNull()?.message

@Composable
fun ApiError.displayMessage(): String = serverMessage() ?: stringResource(displayMessageRes())

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
