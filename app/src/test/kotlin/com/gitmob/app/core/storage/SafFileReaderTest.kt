package com.gitmob.app.core.storage

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import com.gitmob.app.testutil.BlockingInputStream
import com.gitmob.app.testutil.asAssetInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SafFileReaderTest {
    private val resolver = mockk<ContentResolver>()
    private val opened = mutableListOf<String>()
    private val ioThreads = mutableListOf<Thread>()

    private fun document(id: String, name: String = id, stream: InputStream): Uri {
        val uri = Uri.parse("content://test/$id")
        every { resolver.query(uri, any(), null, null, null) } answers {
            ioThreads += Thread.currentThread()
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        }
        every { resolver.openAssetFileDescriptor(uri, "r", any()) } answers {
            ioThreads += Thread.currentThread()
            opened += id
            descriptor(stream)
        }
        return uri
    }

    @Test fun exactCombinedLimitAndZeroByteFileSucceedOffCallerAndCloseStreams() = runTest {
        val caller = Thread.currentThread()
        val a = CountingInput(byteArrayOf(1, 2, 3))
        val b = CountingInput(byteArrayOf(4, 5))
        val empty = CountingInput(byteArrayOf())
        val files = mutableListOf<SafFile>()
        val selection = SafSelection.Documents(listOf(document("a", stream = a), document("b", stream = b), document("empty", stream = empty)))
        SafFileReader(resolver, maxTotalBytes = 5).readEach(selection) { files += it }
        assertEquals(listOf("a", "b", "empty"), files.map { it.relativePath })
        assertEquals(5, files.sumOf { it.bytes.size })
        assertTrue(ioThreads.all { it !== caller })
        assertTrue(listOf(a, b, empty).all { it.closed })
    }

    @Test fun oversizedStreamStopsAtRemainingPlusOneAndNeverOpensNextFile() = runTest {
        val a = CountingInput(ByteArray(3))
        val huge = CountingInput(ByteArray(1000))
        val uris = listOf(document("a", stream = a), document("huge", stream = huge), document("last", stream = CountingInput(byteArrayOf(1))))
        val error = runCatching { SafFileReader(resolver, maxTotalBytes = 5).readEach(SafSelection.Documents(uris)) {} }.exceptionOrNull()
        assertEquals(R.string.repo_upload_too_large, (error as UserVisibleException).messageRes)
        assertEquals(3, huge.readBytes)
        assertTrue(huge.closed)
        assertEquals(listOf("a", "huge"), opened)
    }

    @Test fun tooManySelectedFilesAreRejectedBeforeAnyProviderRead() = runTest {
        val uris = (1..3).map { Uri.parse("content://test/$it") }
        val error = runCatching { SafFileReader(resolver, maxFiles = 2).readEach(SafSelection.Documents(uris)) {} }.exceptionOrNull()
        assertEquals(R.string.repo_upload_too_many_files, (error as UserVisibleException).messageRes)
        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { resolver.openAssetFileDescriptor(any(), "r", any()) }
    }

    @Test fun nestedFolderUsesOneSharedCountAndByteBudget() = runTest {
        val tree = Uri.parse("content://test/tree/root")
        val rootChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root")
        val nestedChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "nested")
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        every { resolver.query(rootChildren, any(), null, null, null) } returns MatrixCursor(columns).apply {
            addRow(arrayOf("a", "a", "text/plain"))
            addRow(arrayOf("nested", "docs", DocumentsContract.Document.MIME_TYPE_DIR))
        }
        every { resolver.query(nestedChildren, any(), null, null, null) } returns MatrixCursor(columns).apply {
            addRow(arrayOf("b", "b", "text/plain"))
            addRow(arrayOf("c", "c", "text/plain"))
        }
        val first = DocumentsContract.buildDocumentUriUsingTree(tree, "a")
        val second = DocumentsContract.buildDocumentUriUsingTree(tree, "b")
        val third = DocumentsContract.buildDocumentUriUsingTree(tree, "c")
        every { resolver.openAssetFileDescriptor(first, "r", any()) } returns descriptor(CountingInput(byteArrayOf(1)))
        every { resolver.openAssetFileDescriptor(second, "r", any()) } returns descriptor(CountingInput(byteArrayOf(2)))
        val paths = mutableListOf<String>()
        val error = runCatching { SafFileReader(resolver, maxFiles = 2).readEach(SafSelection.Tree(tree)) { paths += it.relativePath } }.exceptionOrNull()
        assertEquals(listOf("a", "docs/b"), paths)
        assertEquals(R.string.repo_upload_too_many_files, (error as UserVisibleException).messageRes)
        verify(exactly = 0) { resolver.openAssetFileDescriptor(third, "r", any()) }
    }

    @Test fun cancellationClosesCurrentStreamAndStopsBeforeNextFile() = runTest {
        val first = CountingInput(byteArrayOf(1))
        val uris = listOf(document("first", stream = first), document("next", stream = CountingInput(byteArrayOf(2))))
        val job = launch {
            SafFileReader(resolver).readEach(SafSelection.Documents(uris)) { currentCoroutineContext().cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(first.closed)
        assertEquals(listOf("first"), opened)
    }

    @Test fun readFailureIsLocalizedAndClosesTheStream() = runTest {
        var closed = false
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("provider failed")
            override fun close() { closed = true }
        }
        val uri = document("broken", stream = broken)
        val error = runCatching { SafFileReader(resolver).readEach(SafSelection.Documents(listOf(uri))) {} }.exceptionOrNull()
        assertEquals(R.string.repo_upload_read_failed, (error as UserVisibleException).messageRes)
        assertTrue(closed)
    }

    @Test fun duplicateAndInvalidNamesFailBeforeOpeningTheirStreams() = runTest {
        for (name in listOf("same", "../bad", ".", "")) {
            opened.clear()
            val first = document("first", "same", CountingInput(byteArrayOf(1)))
            val next = document("next", name, CountingInput(byteArrayOf(2)))
            val error = runCatching { SafFileReader(resolver).readEach(SafSelection.Documents(listOf(first, next))) {} }.exceptionOrNull()
            assertEquals(if (name == "same") R.string.repo_upload_duplicate_path else R.string.repo_upload_invalid_path,
                (error as UserVisibleException).messageRes)
            assertEquals(listOf("first"), opened)
        }
    }

    @Test fun cancellationDuringBlockedReadClosesSourceAndNeverConsumesOrOpensNextFile() = runBlocking {
        for (ignoreInterrupts in listOf(false, true)) {
            opened.clear()
            val source = BlockingInputStream(ignoreInterrupts)
            val uris = listOf(document("blocked", stream = source), document("next", stream = CountingInput(byteArrayOf(1))))
            var consumed = false
            val job = launch(Dispatchers.Default) {
                SafFileReader(resolver).readEach(SafSelection.Documents(uris)) { consumed = true }
            }
            try {
                assertTrue(source.reading.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(5_000) { job.join() }
                assertTrue(job.isCancelled)
                assertTrue(source.closed.get())
                assertFalse(consumed)
                assertEquals(listOf("blocked"), opened)
            } finally {
                source.close()
                job.cancelAndJoin()
            }
        }
    }

    private fun descriptor(stream: InputStream): AssetFileDescriptor = mockk(relaxed = true) {
        every { createInputStream() } returns stream.asAssetInputStream()
    }

    private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var readBytes = 0
        var closed = false
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { if (it > 0) readBytes += it }
        override fun close() { closed = true; super.close() }
    }
}
