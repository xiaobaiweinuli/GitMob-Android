package com.gitmob.app.core.network

/** Kept distinct from connection failures so callers can explain a preview limit. */
internal class ResourceSizeLimitException : java.io.IOException("Resource exceeds size limit")
