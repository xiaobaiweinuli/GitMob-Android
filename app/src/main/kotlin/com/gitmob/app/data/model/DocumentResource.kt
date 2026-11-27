package com.gitmob.app.data.model

import java.util.Locale

enum class DocumentResourceKind { IMAGE, VIDEO, AUDIO, UNKNOWN }

/** A resource and its optional enclosing link are independent targets. */
data class DocumentResource(
    val source: String,
    val originalLink: String? = null,
    val description: String? = null,
    val kind: DocumentResourceKind = resourceKind(source),
    val mimeType: String? = null,
)

internal data class DocumentResourceGallery(
    val resources: List<DocumentResource>,
    val initialIndex: Int,
)

data class MediaSource(
    val url: String,
    val kind: DocumentResourceKind,
    val mimeType: String? = null,
    /** Stable identity including the content version; null limits reuse to one playback session. */
    val cacheKey: String? = null,
)

fun resourceKind(path: String, mimeType: String? = null): DocumentResourceKind {
    if (path.startsWith("data:image/", ignoreCase = true)) return DocumentResourceKind.IMAGE
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    when {
        mime?.startsWith("image/") == true -> return DocumentResourceKind.IMAGE
        mime?.startsWith("video/") == true -> return DocumentResourceKind.VIDEO
        mime?.startsWith("audio/") == true -> return DocumentResourceKind.AUDIO
    }
    return when (path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "heic", "heif", "ico" -> DocumentResourceKind.IMAGE
        "mp4", "m4v", "webm", "mkv", "mov", "3gp", "ogv" -> DocumentResourceKind.VIDEO
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr" -> DocumentResourceKind.AUDIO
        else -> DocumentResourceKind.UNKNOWN
    }
}
