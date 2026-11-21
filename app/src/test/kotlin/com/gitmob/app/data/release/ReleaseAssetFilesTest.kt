package com.gitmob.app.data.release

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.gitmob.app.testutil.BlockingInputStream
import com.gitmob.app.testutil.asAssetInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseAssetFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private val resolver = mockk<ContentResolver>()
    private val descriptor = mockk<AssetFileDescriptor>(relaxed = true)
    private val uri = Uri.parse("content://files/document/7")

    @Before fun metadata() {
        every { resolver.openAssetFileDescriptor(uri, "r", any()) } returns descriptor
        every { resolver.query(uri, any(), null, null, null) } answers { MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("asset.zip")) } }
        every { resolver.getType(uri) } returns "application/zip"
        every { descriptor.createInputStream() } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)).asAssetInputStream() }
    }

    @Test fun metadataAndReadsRunOnIoAndUnknownLengthIsCountedInBoundedChunks() = runTest {
        Executors.newSingleThreadExecutor { task -> Thread(task, "release-io-test") }.asCoroutineDispatcher().use { io ->
            val threads = mutableListOf<String>()
            every { resolver.query(uri, any(), null, null, null) } answers { threads += Thread.currentThread().name; MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("large.bin")) } }
            every { resolver.getType(uri) } answers { threads += Thread.currentThread().name; null }
            var closed = false
            var biggestRead = 0
            val size = 4 * 1024 * 1024 + 17
            every { descriptor.createInputStream() } answers {
                threads += Thread.currentThread().name
                object : InputStream() {
                    var remaining = size
                    override fun read(): Int = error("Chunked reads required")
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        threads += Thread.currentThread().name
                        biggestRead = maxOf(biggestRead, length)
                        if (remaining == 0) return -1
                        val count = minOf(length, remaining)
                        bytes.fill(42, offset, offset + count); remaining -= count
                        return count
                    }
                    override fun close() { closed = true }
                }.asAssetInputStream()
            }
            val files = ReleaseAssetFiles(resolver, folder.root, io)
            lateinit var copy: File
            files.withPreparedAsset(uri) { asset ->
                copy = asset.file
                assertEquals(size.toLong(), asset.length)
                assertEquals(size.toLong(), copy.length())
                assertEquals("large.bin", asset.name)
                assertEquals("application/octet-stream", asset.contentType)
                assertTrue(closed)
            }
            assertFalse(copy.exists())
            assertEquals(64 * 1024, biggestRead)
            assertTrue(threads.isNotEmpty())
            assertTrue(threads.all { it == "release-io-test" })
        }
    }

    @Test fun successfulUseRemovesOnlyItsOwnTemporaryFile() = runTest {
        val unrelated = folder.newFile("other-upload.tmp")
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        lateinit var temporary: File
        val result = files.withPreparedAsset(uri) { asset -> temporary = asset.file; assertTrue(temporary.exists()); "success" }
        assertEquals("success", result)
        assertFalse(temporary.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun uploadFailureStillCleansPrivateCopyAndRetainsOriginalError() = runTest {
        val failure = IOException("network")
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        val error = runCatching { files.withPreparedAsset(uri) { throw failure } }.exceptionOrNull()
        assertSame(failure, error)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationInsideUploadCleansFileAndPropagatesCancellation() = runTest {
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        val task = launch { files.withPreparedAsset(uri) { awaitCancellation() } }
        runCurrent()
        assertEquals(1, folder.root.listFiles()!!.size)
        task.cancelAndJoin()
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationWhileCopyingClosesSourceAndDeletesPartialFile() = runTest {
        val cancelled = Job(coroutineContext[Job])
        var closed = false
        var reads = 0
        every { descriptor.createInputStream() } returns object : InputStream() {
            override fun read(): Int = error("Chunked reads required")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; cancelled.cancel(); return length }
            override fun close() { closed = true }
        }.asAssetInputStream()
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        var used = false
        val error = runCatching { withContext(cancelled) { files.withPreparedAsset(uri) { used = true } } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(used)
        assertTrue(closed)
        assertEquals(1, reads)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationOfProviderIgnoringInterruptsClosesSourceAndDeletesPartialCopy() = runBlocking {
        val source = BlockingInputStream(ignoreInterrupts = true)
        every { descriptor.createInputStream() } returns source.asAssetInputStream()
        var used = false
        val task = launch(Dispatchers.Default) {
            ReleaseAssetFiles(resolver, folder.root).withPreparedAsset(uri) { used = true }
        }
        try {
            assertTrue(source.reading.await(5, TimeUnit.SECONDS))
            assertEquals(1, folder.root.listFiles()!!.size)
            task.cancel()
            withTimeout(5_000) { task.join() }
            assertTrue(source.closed.get())
            assertFalse(used)
            assertTrue(folder.root.listFiles()!!.isEmpty())
        } finally {
            source.close()
            task.cancelAndJoin()
        }
    }

    @Test fun expiredPermissionIsReadableFailureAndLeavesNoCopy() = runTest {
        every { descriptor.createInputStream() } throws SecurityException("expired")
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        val error = runCatching { files.withPreparedAsset(uri) { error("must not upload") } }.exceptionOrNull()
        assertEquals(R.string.release_asset_read_failed, (error as UserVisibleException).messageRes)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun sourceReadFailureAndNullStreamAreReportedAsFileErrors() = runTest {
        every { descriptor.createInputStream() } returns object : InputStream() { override fun read(): Int = throw IOException("read failed") }.asAssetInputStream()
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        var error = runCatching { files.withPreparedAsset(uri) { } }.exceptionOrNull()
        assertEquals(R.string.release_asset_read_failed, (error as UserVisibleException).messageRes)
        assertTrue(folder.root.listFiles()!!.isEmpty())
        every { resolver.openAssetFileDescriptor(uri, "r", any()) } returns null
        error = runCatching { files.withPreparedAsset(uri) { } }.exceptionOrNull()
        assertEquals(R.string.release_asset_read_failed, (error as UserVisibleException).messageRes)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun unavailableStorageIsReportedBeforeAnyNetworkUse() = runTest {
        val files = ReleaseAssetFiles(resolver, File(folder.root, "missing/directory"), StandardTestDispatcher(testScheduler))
        val error = runCatching { files.withPreparedAsset(uri) { error("must not upload") } }.exceptionOrNull()
        assertEquals(R.string.release_asset_storage_failed, (error as UserVisibleException).messageRes)
        verify(exactly = 0) { resolver.openAssetFileDescriptor(any(), "r", any()) }
    }

    @Test fun missingMetadataAndEmptyContentHaveSafeDefaults() = runTest {
        every { resolver.query(uri, any(), null, null, null) } returns null
        every { resolver.getType(uri) } returns null
        every { descriptor.createInputStream() } returns ByteArrayInputStream(byteArrayOf()).asAssetInputStream()
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        files.withPreparedAsset(uri) {
            assertEquals("asset.bin", it.name)
            assertEquals("application/octet-stream", it.contentType)
            assertEquals(0L, it.length)
        }
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun sourceCloseFailureUsesFileErrorAndStillCleansCopy() = runTest {
        every { descriptor.createInputStream() } returns object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { throw IOException("close failed") }
        }.asAssetInputStream()
        val files = ReleaseAssetFiles(resolver, folder.root, StandardTestDispatcher(testScheduler))
        val error = runCatching { files.withPreparedAsset(uri) { error("must not upload") } }.exceptionOrNull()
        assertEquals(R.string.release_asset_read_failed, (error as UserVisibleException).messageRes)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
