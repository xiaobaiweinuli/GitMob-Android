package com.gitmob.app.core.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Keeps provider opening, stream ownership and cancellation in one scope. */
internal suspend fun <T> ContentResolver.readUri(
    uri: Uri,
    ioDispatcher: CoroutineDispatcher,
    read: (InputStream) -> T,
): T? = coroutineScope {
    val signal = CancellationSignal()
    // A separate IO worker can close a blocked source even if it ignores thread interruption.
    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            signal.cancel()
        }
    }
    try {
        val context = currentCoroutineContext()
        runInterruptible(ioDispatcher) {
            openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    signal.setOnCancelListener { runCatching { input.close() } }
                    try {
                        context.ensureActive()
                        read(input)
                    } finally {
                        signal.setOnCancelListener(null)
                    }
                }
            }
        }
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        cancellation.cancel()
    }
}
