package com.gitmob.app.core.storage

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.gitmob.app.testutil.asAssetInputStream
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ContentUriReadTest {
    private val resolver = mockk<ContentResolver>()
    private val uri = Uri.parse("content://files/document/1")

    @Test fun cancellationReachesProviderWhileOpeningAndClosesAnyLateDescriptor() = runBlocking {
        for (returnLateDescriptor in listOf(false, true)) {
            val opening = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val streamClosed = AtomicBoolean(false)
            val descriptor = mockk<AssetFileDescriptor>(relaxed = true)
            every { descriptor.createInputStream() } returns object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { streamClosed.set(true) }
            }.asAssetInputStream()
            every { resolver.openAssetFileDescriptor(uri, "r", any()) } answers {
                val signal = thirdArg<CancellationSignal>()
                signal.setOnCancelListener { cancelled.countDown() }
                opening.countDown()
                while (true) {
                    try {
                        check(cancelled.await(10, TimeUnit.SECONDS)) { "Provider was not cancelled" }
                        break
                    } catch (_: InterruptedException) {
                        // This provider responds only to CancellationSignal.
                    }
                }
                if (returnLateDescriptor) descriptor else throw OperationCanceledException()
            }
            var consumed = false
            val job = launch(Dispatchers.Default) {
                resolver.readUri(uri, Dispatchers.IO) { consumed = true }
            }
            try {
                assertTrue(opening.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(5_000) { job.join() }
                assertEquals(0L, cancelled.count)
                assertTrue(job.isCancelled)
                assertFalse(consumed)
                if (returnLateDescriptor) {
                    assertTrue(streamClosed.get())
                    verify(exactly = 1) { descriptor.close() }
                }
            } finally {
                cancelled.countDown()
                job.cancelAndJoin()
            }
        }
    }
}
