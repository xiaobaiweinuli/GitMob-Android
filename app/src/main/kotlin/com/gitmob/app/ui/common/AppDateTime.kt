package com.gitmob.app.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.gitmob.app.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.TimeZone

private val LocalDateTimeFormatter = compositionLocalOf<SimpleDateFormat?> { null }

@Composable
fun ProvideDateTimeFormatter(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDateTimeFormatter provides rememberDateTimeFormatter(), content = content)
}

@Composable
private fun rememberDateTimeFormatter(): SimpleDateFormat {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var is24Hour by remember(context, locale) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    var zone by remember { mutableStateOf(TimeZone.getDefault()) }

    DisposableEffect(context, locale) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                is24Hour = DateFormat.is24HourFormat(context)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                zone = TimeZone.getDefault()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer,
        )
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        is24Hour = DateFormat.is24HourFormat(context)
        zone = TimeZone.getDefault()
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    return remember(locale, is24Hour, zone) {
        val skeleton = if (is24Hour) "yMdHms" else "yMdhms"
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply {
            timeZone = zone
        }
    }
}

@Composable
fun formatDateTime(value: String?): String {
    val timestamp = remember(value) {
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
    return if (timestamp == null) stringResource(R.string.common_unknown) else formatDateTime(timestamp)
}

@Composable
fun formatDateTime(timestamp: Long): String {
    val formatter = LocalDateTimeFormatter.current ?: rememberDateTimeFormatter()
    return remember(timestamp, formatter) { formatter.format(Date(timestamp)) }
}
