package com.gitmob.app.data.release

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal data class PreparedReleaseAsset(val file: File, val name: String, val contentType: String, val length: Long)

/** Owns only the temporary file for this invocation, including cancellation during dispatcher handoff. */
@Singleton
class ReleaseAssetFiles internal constructor(
    private val resolver: ContentResolver,
    private val cacheDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.contentResolver, context.cacheDir)

    internal suspend fun <T> withPreparedAsset(uri: Uri, use: suspend (PreparedReleaseAsset) -> T): T {
        var temporary: File? = null
        try {
            val context = currentCoroutineContext()
            val asset = runInterruptible(ioDispatcher) {
                context.ensureActive()
                val name = readFile {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    }
                }?.takeIf(String::isNotBlank) ?: "asset.bin"
                context.ensureActive()
                val type = readFile { resolver.getType(uri) }?.takeIf(String::isNotBlank) ?: "application/octet-stream"
                val file = writeFile { File.createTempFile("release-upload-", ".tmp", cacheDirectory) }
                temporary = file
                var length = 0L
                val input = readFile { resolver.openInputStream(uri) }
                    ?: throw UserVisibleException(R.string.release_asset_read_failed)
                readFile {
                    input.use { source ->
                        writeFile {
                            file.outputStream().use { destination ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    context.ensureActive()
                                    val count = readFile { source.read(buffer) }
                                    if (count < 0) break
                                    context.ensureActive()
                                    destination.write(buffer, 0, count)
                                    length += count
                                }
                            }
                        }
                    }
                }
                context.ensureActive()
                PreparedReleaseAsset(file, name, type, length)
            }
            return use(asset)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                temporary?.let { file ->
                    // Cleanup must not replace cancellation or a successful server response with another error.
                    if (file.exists() && !file.delete()) Log.w("ReleaseAssetFiles", "Unable to remove private upload copy")
                }
            }
        }
    }

    private fun <T> readFile(block: () -> T): T = try { block() }
    catch (_: IOException) { throw UserVisibleException(R.string.release_asset_read_failed) }
    catch (_: SecurityException) { throw UserVisibleException(R.string.release_asset_read_failed) }

    private fun <T> writeFile(block: () -> T): T = try { block() }
    catch (_: IOException) { throw UserVisibleException(R.string.release_asset_storage_failed) }
    catch (_: SecurityException) { throw UserVisibleException(R.string.release_asset_storage_failed) }
}
