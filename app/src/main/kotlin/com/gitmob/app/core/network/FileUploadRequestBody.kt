package com.gitmob.app.core.network

import java.io.File
import java.io.IOException
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Fixed memory use; callbacks run on the OkHttp writer thread and are throttled to ten per second. */
internal class FileUploadRequestBody(
    private val file: File,
    private val type: MediaType,
    private val length: Long,
    private val checkActive: () -> Unit,
    private val progress: (Long, Long) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
) : RequestBody() {
    private val writerLock = Any()
    fun awaitIdle() { synchronized(writerLock) { } }

    override fun contentType(): MediaType = type
    override fun contentLength(): Long = length
    // Upload creation must not be replayed after a partially transmitted body.
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        synchronized(writerLock) { writeFileTo(sink) }
    }

    private fun writeFileTo(sink: BufferedSink) {
        checkActive()
        var sent = 0L
        var lastUpdate = nowNanos()
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = source.read(buffer)
                if (count < 0) break
                checkActive()
                if (sent + count > length) throw IOException("Upload file length changed")
                sink.write(buffer, 0, count)
                sent += count
                val now = nowNanos()
                if (sent == length || now - lastUpdate >= 100_000_000L) {
                    checkActive()
                    progress(sent, length)
                    lastUpdate = now
                }
            }
        }
        checkActive()
        if (sent != length) throw IOException("Upload file length changed")
        if (length == 0L) progress(0L, 0L)
    }
}
