package com.gitmob.app.testutil

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Models both interruptible providers and providers released only by closing their stream. */
internal class BlockingInputStream(private val ignoreInterrupts: Boolean) : InputStream() {
    val reading = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    private val released = CountDownLatch(1)

    override fun read(): Int {
        reading.countDown()
        while (true) {
            try {
                check(released.await(10, TimeUnit.SECONDS)) { "Read was not cancelled" }
                return -1
            } catch (error: InterruptedException) {
                if (!ignoreInterrupts) throw error
            }
        }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = read()

    override fun close() {
        closed.set(true)
        released.countDown()
    }
}
