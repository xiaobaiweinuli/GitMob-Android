package com.gitmob.app.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface SafSelection {
    data class Documents(val uris: List<Uri>) : SafSelection
    data class Tree(val uri: Uri) : SafSelection
}

data class SafFile(val relativePath: String, val bytes: ByteArray)

/** Reads one bounded file at a time. The consumer must finish before the next file is opened. */
@Singleton
class SafFileReader internal constructor(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxFiles: Int = MAX_FILES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.contentResolver)

    suspend fun readEach(selection: SafSelection, consume: suspend (SafFile) -> Unit) = withContext(ioDispatcher) {
        val paths = mutableSetOf<String>()
        var totalBytes = 0L
        suspend fun readFile(uri: Uri, path: String) {
            currentCoroutineContext().ensureActive()
            if (paths.size >= maxFiles) throw UserVisibleException(R.string.repo_upload_too_many_files)
            if (path.isBlank() || path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
                throw UserVisibleException(R.string.repo_upload_invalid_path)
            }
            if (!paths.add(path)) throw UserVisibleException(R.string.repo_upload_duplicate_path)
            val context = currentCoroutineContext()
            val bytes = resolver.readUri(uri, ioDispatcher) { readBounded(it, maxTotalBytes - totalBytes, context) }
                ?: throw UserVisibleException(R.string.repo_upload_read_failed)
            totalBytes += bytes.size
            consume(SafFile(path, bytes))
        }
        suspend fun walk(tree: Uri, parent: String, prefix: String) {
            currentCoroutineContext().ensureActive()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn)
                    if (name.isNullOrBlank() || name == "." || name == ".." || '/' in name) {
                        throw UserVisibleException(R.string.repo_upload_invalid_path)
                    }
                    val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(tree, id, relative)
                    } else {
                        readFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), relative)
                    }
                }
            } ?: throw UserVisibleException(R.string.repo_upload_read_failed)
        }
        try {
            when (selection) {
                is SafSelection.Documents -> {
                    if (selection.uris.size > maxFiles) throw UserVisibleException(R.string.repo_upload_too_many_files)
                    for (uri in selection.uris) {
                        currentCoroutineContext().ensureActive()
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                        } ?: throw UserVisibleException(R.string.repo_upload_read_failed)
                        if ('/' in name) throw UserVisibleException(R.string.repo_upload_invalid_path)
                        readFile(uri, name)
                    }
                }
                is SafSelection.Tree -> walk(selection.uri, DocumentsContract.getTreeDocumentId(selection.uri), "")
            }
            if (paths.isEmpty()) throw UserVisibleException(R.string.repo_upload_empty)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw UserVisibleException(R.string.repo_upload_read_failed)
        } catch (_: SecurityException) {
            currentCoroutineContext().ensureActive()
            throw UserVisibleException(R.string.repo_upload_read_failed)
        }
    }

    companion object {
        const val MAX_FILES = 100
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

        private fun readBounded(input: InputStream, remaining: Long, context: CoroutineContext): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var size = 0L
            while (true) {
                context.ensureActive()
                // Read at most one byte beyond the budget, including when the budget is zero.
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining - size + 1).toInt())
                if (count < 0) break
                context.ensureActive()
                size += count
                if (size > remaining) throw UserVisibleException(R.string.repo_upload_too_large)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
