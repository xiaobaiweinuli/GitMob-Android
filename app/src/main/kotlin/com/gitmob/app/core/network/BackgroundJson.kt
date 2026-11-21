package com.gitmob.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keep serialization CPU work off callers such as Main, using the client's Json configuration. */
@PublishedApi
internal suspend inline fun <reified T> Json.decodeOnDefault(text: String): T =
    withContext(Dispatchers.Default) { decodeFromString<T>(text) }

@PublishedApi
internal suspend inline fun <reified T> Json.decodeOnDefault(bytes: ByteArray): T =
    withContext(Dispatchers.Default) { decodeFromString<T>(bytes.decodeToString()) }

@PublishedApi
internal suspend inline fun <reified T> Json.encodeOnDefault(value: T): String =
    withContext(Dispatchers.Default) { encodeToString(value) }
