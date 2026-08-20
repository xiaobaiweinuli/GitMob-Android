package com.gitmob.app.core.storage

import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.net.Uri
import java.io.IOException

data class SafFile(val relativePath: String, val bytes: ByteArray)

object SafFileReader {
    const val MAX_FILES = 100
    const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

    fun readDocuments(context: Context, uris: List<Uri>): List<SafFile> {
        val files = uris.map { uri ->
            val name = displayName(context, uri) ?: throw IOException("Selected file has no name")
            SafFile(name, readBytes(context, uri))
        }
        validate(files)
        return files
    }

    fun readTree(context: Context, treeUri: Uri): List<SafFile> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val result = mutableListOf<SafFile>()
        walkTree(context, treeUri, rootId, "", result)
        validate(result)
        return result
    }

    private fun walkTree(context: Context, treeUri: Uri, parentId: String, prefix: String, result: MutableList<SafFile>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                val mime = cursor.getString(mimeIndex)
                val relative = if (prefix.isBlank()) name else "$prefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walkTree(context, treeUri, id, relative, result)
                else result += SafFile(relative, readBytes(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, id)))
                if (result.size > MAX_FILES) throw IOException("Too many files")
            }
        } ?: throw IOException("Unable to read selected folder")
    }

    private fun displayName(context: Context, uri: Uri): String? = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Unable to read selected file")

    private fun validate(files: List<SafFile>) {
        require(files.isNotEmpty()) { "No files selected" }
        require(files.size <= MAX_FILES) { "Too many files" }
        require(files.map { it.relativePath }.toSet().size == files.size) { "Duplicate file path" }
        require(files.sumOf { it.bytes.size.toLong() } <= MAX_TOTAL_BYTES) { "Selected files are too large" }
        require(files.all { it.relativePath.isNotBlank() && !it.relativePath.split('/').any { segment -> segment == "." || segment == ".." } }) { "Invalid file path" }
    }
}
