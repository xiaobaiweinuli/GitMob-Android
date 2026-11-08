package com.gitmob.app.data.model

data class AppUpdatePreferences(
    val automatic: Boolean = true,
    val ignoredVersionCode: Int? = null,
    val lastSuccessfulCheckAt: Long = 0,
)

data class AppUpdate(
    val versionName: String,
    val versionCode: Int,
    val title: String,
    val notesHtml: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

sealed interface AppUpdateCheck {
    data class Available(val update: AppUpdate) : AppUpdateCheck
    data object UpToDate : AppUpdateCheck
    data object NoCompatibleRelease : AppUpdateCheck
}
