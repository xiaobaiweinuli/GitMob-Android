package com.gitmob.app.testutil

import io.mockk.every
import io.mockk.mockk
import java.io.FileInputStream
import java.io.InputStream

internal fun InputStream.asAssetInputStream(): FileInputStream {
    val source = this
    return mockk {
        every { read() } answers { source.read() }
        every { read(any<ByteArray>()) } answers { source.read(firstArg()) }
        every { read(any<ByteArray>(), any(), any()) } answers { source.read(firstArg(), secondArg(), thirdArg()) }
        every { close() } answers { source.close() }
    }
}
