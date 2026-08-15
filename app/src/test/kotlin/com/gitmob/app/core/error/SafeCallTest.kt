package com.gitmob.app.core.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class SafeCallTest {

    @Test
    fun `safeCall rethrows coroutine cancellation`() = runTest {
        try {
            safeCall<Unit> { throw CancellationException("filter changed") }
            fail("CancellationException should be rethrown")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
