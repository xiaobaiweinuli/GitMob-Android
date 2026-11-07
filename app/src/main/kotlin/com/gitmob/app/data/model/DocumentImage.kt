package com.gitmob.app.data.model

/** The displayed image source and its optional enclosing link are independent targets. */
data class DocumentImage(
    val source: String,
    val originalLink: String? = null,
    val description: String? = null,
)

internal fun isPreviewableImage(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in
        setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "heic", "heif", "ico")
