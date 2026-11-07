package com.gitmob.app.data.repository

import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.REPO_DOCUMENT_HOST
import com.gitmob.app.core.markdown.imageResourcePath
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.ResourceSizeLimitException
import com.gitmob.app.data.model.RepoDocumentContext
import java.net.URLConnection
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MAX_DOCUMENT_RESOURCE_BYTES = 8 * 1024 * 1024

data class RepoDocumentResource(val bytes: ByteArray, val mimeType: String)

/** Repository resources use account authentication; public image URLs never receive account headers. */
@Singleton
class RepoDocumentRepository @Inject constructor(
    private val api: GHApiClient,
    private val updates: RepoUpdateEventBus,
) {
    private data class ResourceKey(val owner: String, val name: String, val ref: String, val path: String)

    private class SessionResources(val epoch: AccountEpoch) {
        // Requests belong to the account, so closing one consumer only cancels its wait.
        val scope = CoroutineScope(epoch.job + Dispatchers.IO + AccountContext(epoch))
        val permits = Semaphore(4)
        val cache = LinkedHashMap<ResourceKey, RepoDocumentResource>(16, 0.75f, true)
        val inFlight = mutableMapOf<ResourceKey, Deferred<ApiResult<RepoDocumentResource>>>()
        var cachedBytes = 0
    }

    private val lock = Any()
    private var sessionResources: SessionResources? = null

    private fun key(document: RepoDocumentContext, path: String) = ResourceKey(
        document.owner.lowercase(Locale.ROOT), document.name.lowercase(Locale.ROOT), document.ref, path,
    )

    // Accessed under lock. No token is retained in a cache key or in the cached data.
    private fun resources(epoch: AccountEpoch): SessionResources {
        epoch.ensureCurrent()
        sessionResources?.takeIf { it.epoch === epoch }?.let { return it }
        return SessionResources(epoch).also { resources ->
            sessionResources = resources
            resources.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                updates.eventsFor(epoch).collect { event ->
                    when (event) {
                        is RepoUpdateEvent.CodeChanged -> event.changedPaths.forEach { path ->
                            invalidate(RepoDocumentContext(event.owner, event.name, event.ref, path), path)
                        }
                        is RepoUpdateEvent.BranchSynced ->
                            invalidate(RepoDocumentContext(event.owner, event.name, event.ref, ""))
                        else -> Unit
                    }
                }
            }
            epoch.job.invokeOnCompletion {
                synchronized(lock) {
                    resources.cache.clear()
                    resources.inFlight.clear()
                    resources.cachedBytes = 0
                    if (sessionResources === resources) sessionResources = null
                }
            }
        }
    }

    /** Discards this ref's resources, or just one image when retrying a failed decode. */
    fun invalidate(document: RepoDocumentContext, path: String? = null) {
        val epoch = AccountContext.ensureCurrent()
        val target = key(document, path.orEmpty())
        synchronized(lock) {
            val resources = sessionResources?.takeIf { it.epoch === epoch } ?: return
            fun matches(key: ResourceKey) = key.owner == target.owner && key.name == target.name &&
                key.ref == target.ref && (path == null || key.path == path)
            val iterator = resources.cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (matches(entry.key)) {
                    resources.cachedBytes -= entry.value.bytes.size
                    iterator.remove()
                }
            }
            // Existing consumers may finish; new readers must not join the old download.
            resources.inFlight.keys.removeAll(::matches)
        }
    }

    suspend fun load(document: RepoDocumentContext, path: String): ApiResult<RepoDocumentResource> {
        currentCoroutineContext().ensureActive()
        val epoch = AccountContext.ensureCurrent()
        val key = key(document, path)
        val pending = synchronized(lock) {
            val resources = resources(epoch)
            resources.cache[key]?.let { return ApiResult.Success(it) }
            resources.inFlight[key] ?: resources.scope.async(start = CoroutineStart.LAZY) {
                resources.permits.withPermit {
                    val result = fetch(document, path)
                    val requestJob = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        epoch.ensureCurrent()
                        // An invalidated request cannot put stale bytes back into the cache.
                        if (result is ApiResult.Success && resources.inFlight[key] === requestJob) {
                            resources.cache[key] = result.data
                            resources.cachedBytes += result.data.bytes.size
                            while (resources.cachedBytes > 16 * 1024 * 1024 || resources.cache.size > 64) {
                                val iterator = resources.cache.entries.iterator()
                                resources.cachedBytes -= iterator.next().value.bytes.size
                                iterator.remove()
                            }
                        }
                    }
                    result
                }
            }.also { request ->
                resources.inFlight[key] = request
                request.invokeOnCompletion { synchronized(lock) { resources.inFlight.remove(key, request) } }
            }
        }
        return pending.await().also { currentCoroutineContext().ensureActive(); epoch.ensureCurrent() }
    }

    suspend fun loadImage(source: String, document: RepoDocumentContext?): ApiResult<RepoDocumentResource> {
        val repositoryPath = document?.imageResourcePath(source)
        if (repositoryPath != null) return load(document, repositoryPath)
        return safeCall {
            if (source.startsWith("data:image/", ignoreCase = true)) withContext(Dispatchers.Default) {
                val separator = source.indexOf(',')
                require(separator >= 0)
                val header = source.substring(0, separator)
                val bytes = if (header.endsWith(";base64", ignoreCase = true)) {
                    decodeBase64Image(source, separator + 1)
                } else {
                    decodePercentEncodedImage(source, separator + 1)
                }
                RepoDocumentResource(bytes, header.substringAfter(':').substringBefore(';'))
            } else {
                val url = requireNotNull(source.toHttpUrlOrNull())
                require(url.host != REPO_DOCUMENT_HOST && url.username.isEmpty() && url.password.isEmpty())
                val bytes = imageBytes { api.getPublicImageBytes(url.toString(), MAX_DOCUMENT_RESOURCE_BYTES) }
                RepoDocumentResource(bytes, URLConnection.guessContentTypeFromName(url.encodedPath) ?: "application/octet-stream")
            }
        }
    }

    private fun decodeBase64Image(source: String, start: Int): ByteArray {
        var encodedLength = source.length - start
        // Ignore up to two padding characters; the decoder validates their placement.
        repeat(2) {
            if (encodedLength > 0 && source[start + encodedLength - 1] == '=') encodedLength--
        }
        val decodedSize = encodedLength.toLong() * 3 / 4
        if (decodedSize > MAX_DOCUMENT_RESOURCE_BYTES) throw UserVisibleException(R.string.image_preview_too_large)
        return java.util.Base64.getDecoder().decode(source.substring(start))
    }

    private fun decodePercentEncodedImage(source: String, start: Int): ByteArray {
        fun decodeInto(output: ByteArray?): Int {
            var size = 0
            fun append(value: Int) {
                if (size == MAX_DOCUMENT_RESOURCE_BYTES) throw UserVisibleException(R.string.image_preview_too_large)
                if (output != null) output[size] = value.toByte()
                size++
            }
            var index = start
            while (index < source.length) {
                if (source[index] == '%') {
                    require(source.length - index >= 3)
                    val high = requireNotNull(source[index + 1].digitToIntOrNull(16))
                    val low = requireNotNull(source[index + 2].digitToIntOrNull(16))
                    append((high shl 4) or low)
                    index += 3
                } else {
                    val codePoint = source.codePointAt(index)
                    index += Character.charCount(codePoint)
                    when {
                        codePoint < 0x80 -> append(codePoint) // Includes literal '+'.
                        codePoint < 0x800 -> {
                            append(0xC0 or (codePoint shr 6))
                            append(0x80 or (codePoint and 0x3F))
                        }
                        codePoint in 0xD800..0xDFFF -> append('?'.code)
                        codePoint < 0x10000 -> {
                            append(0xE0 or (codePoint shr 12))
                            append(0x80 or ((codePoint shr 6) and 0x3F))
                            append(0x80 or (codePoint and 0x3F))
                        }
                        else -> {
                            append(0xF0 or (codePoint shr 18))
                            append(0x80 or ((codePoint shr 12) and 0x3F))
                            append(0x80 or ((codePoint shr 6) and 0x3F))
                            append(0x80 or (codePoint and 0x3F))
                        }
                    }
                }
            }
            return size
        }
        // Validate and count before allocating the exact output size, without copying the payload.
        return ByteArray(decodeInto(null)).also { decodeInto(it) }
    }

    private suspend fun imageBytes(load: suspend () -> ByteArray): ByteArray = try {
        load()
    } catch (_: ResourceSizeLimitException) {
        throw UserVisibleException(R.string.image_preview_too_large)
    }

    private suspend fun fetch(document: RepoDocumentContext, path: String): ApiResult<RepoDocumentResource> = safeCall {
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == "." || it == ".." })
        val url = "https://api.github.com/".toHttpUrl().newBuilder()
            .addPathSegment("repos").addPathSegment(document.owner).addPathSegment(document.name)
            .addPathSegment("contents").addPathSegments(path)
            .addQueryParameter("ref", document.ref).build()
        val bytes = imageBytes { api.getRawBytes(
            "${url.encodedPath}?${url.encodedQuery}",
            "application/vnd.github.raw+json",
            maxBytes = MAX_DOCUMENT_RESOURCE_BYTES,
        ) }
        val mime = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "ico" -> "image/x-icon"
            "css" -> "text/css"
            else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
        }
        RepoDocumentResource(bytes, mime)
    }
}
