package com.gitmob.app.core.network

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUploadRequestBodyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun bodyStreamsExactBinaryLengthAndThrottlesProgress() {
        val file = folder.newFile()
        file.outputStream().use { output -> repeat(160) { output.write(ByteArray(64 * 1024) { (it % 251).toByte() }) } }
        val updates = mutableListOf<Long>()
        var time = 0L
        val body = FileUploadRequestBody(file, "application/zip".toMediaType(), file.length(), {}, { sent, total -> assertEquals(file.length(), total); updates += sent }, { time += 1_000_000L; time })
        var received = 0L
        var maximumWrite = 0L
        val sink = object : okio.Sink {
            override fun write(source: Buffer, byteCount: Long) { maximumWrite = maxOf(maximumWrite, byteCount); received += byteCount; source.skip(byteCount) }
            override fun flush() { }
            override fun close() { }
            override fun timeout() = okio.Timeout.NONE
        }.buffer()
        body.writeTo(sink); sink.flush()
        assertEquals(file.length(), body.contentLength())
        assertEquals(file.length(), received)
        assertTrue(maximumWrite <= 64 * 1024)
        assertEquals("application/zip", body.contentType().toString())
        assertTrue(body.isOneShot())
        assertTrue(updates.size <= 3)
        assertEquals(file.length(), updates.last())
    }

    @Test fun zeroLengthHasValidLengthAndCompletion() {
        val file = folder.newFile()
        val updates = mutableListOf<Pair<Long, Long>>()
        val body = FileUploadRequestBody(file, "application/octet-stream".toMediaType(), 0, {}, { sent, total -> updates += sent to total })
        val sink = Buffer(); body.writeTo(sink)
        assertEquals(0L, body.contentLength())
        assertEquals(0L, sink.size)
        assertEquals(listOf(0L to 0L), updates)
    }

    @Test fun cancellationBetweenChunksStopsReadingAndReleasesFile() {
        val file = folder.newFile().apply { writeBytes(ByteArray(256 * 1024)) }
        var checks = 0
        val body = FileUploadRequestBody(file, "application/octet-stream".toMediaType(), file.length(), { if (++checks == 4) throw InterruptedIOException("cancelled") }, { _, _ -> })
        val sink = Buffer()
        val error = runCatching { body.writeTo(sink) }.exceptionOrNull()
        assertTrue(error is InterruptedIOException)
        assertTrue(sink.size < file.length())
        body.awaitIdle()
        assertTrue(file.delete())
    }

    @Test fun writerFailureClosesFileAndPreservesFailure() {
        val file = folder.newFile().apply { writeBytes(ByteArray(128 * 1024)) }
        val failure = IOException("socket closed")
        val sink = object : ForwardingSink(Buffer()) {
            override fun write(source: Buffer, byteCount: Long) { throw failure }
        }.buffer()
        val body = FileUploadRequestBody(file, "application/octet-stream".toMediaType(), file.length(), {}, { _, _ -> })
        assertSame(failure, runCatching { body.writeTo(sink) }.exceptionOrNull())
        body.awaitIdle()
        assertTrue(file.delete())
    }

    @Test fun fileLengthChangesAreRejectedInsteadOfSendingInvalidContentLength() {
        val file = folder.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        for (expected in listOf(1L, 3L)) {
            val body = FileUploadRequestBody(file, "application/octet-stream".toMediaType(), expected, {}, { _, _ -> })
            assertTrue(runCatching { body.writeTo(Buffer()) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun cleanupWaitsUntilAnActiveWriterReleasesItsStream() {
        val file = folder.newFile().apply { writeBytes(ByteArray(128 * 1024)) }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val idle = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val sink = object : ForwardingSink(Buffer()) {
            override fun write(source: Buffer, byteCount: Long) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                super.write(source, byteCount)
            }
        }.buffer()
        val body = FileUploadRequestBody(file, "application/octet-stream".toMediaType(), file.length(), {}, { _, _ -> })
        val writer = Thread { try { body.writeTo(sink) } catch (error: Throwable) { failure.set(error) } }
        val cleaner = Thread { body.awaitIdle(); idle.countDown() }
        try {
            writer.start()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            cleaner.start()
            assertFalse(idle.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            writer.join(5000)
            cleaner.join(5000)
        }
        assertFalse(writer.isAlive)
        assertFalse(cleaner.isAlive)
        assertNull(failure.get())
        assertEquals(0L, idle.count)
        assertTrue(file.delete())
    }
}
