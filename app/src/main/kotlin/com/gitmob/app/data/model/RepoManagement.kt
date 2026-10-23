package com.gitmob.app.data.model

import java.net.URI

data class RepositorySubscription(val state: String, val watcherCount: Int)

data class RepositoryTransferTarget(
    val account: RepositoryCreateOwner,
    val hasNameConflict: Boolean,
)

fun isValidRepositoryName(value: String): Boolean =
    value.length in 1..100 && value != "." && value != ".." && value.matches(Regex("[A-Za-z0-9_.-]+"))

fun isValidRepositoryWebsite(value: String): Boolean {
    if (value.isEmpty()) return true
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank() && uri.userInfo == null
}

fun parseRepositoryTopics(value: String): List<String> =
    value.split(Regex("[,\\s]+")).filter(String::isNotBlank).distinct()

fun areValidRepositoryTopics(topics: List<String>): Boolean =
    topics.size <= 20 && topics.all { it.length in 1..50 && it.matches(Regex("[a-z0-9][a-z0-9-]*")) }
