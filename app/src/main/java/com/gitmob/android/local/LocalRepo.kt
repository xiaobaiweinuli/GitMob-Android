package com.gitmob.android.local

import com.google.gson.annotations.SerializedName

/** 本地仓库状态 */
enum class LocalRepoStatus {
    /** 目录中存在 .git，可直接管理 */
    GIT_INITIALIZED,
    /** 目录中没有 .git，需要初始化 */
    PENDING_INIT,
    /** 正在进行 git 操作 */
    WORKING,
    /** 克隆/操作出错 */
    ERROR,
}

data class LocalRepo(
    @SerializedName("id")     val id: String,
    @SerializedName("path")   val path: String,
    @SerializedName("name")   val name: String,
    @SerializedName("status") val status: LocalRepoStatus = LocalRepoStatus.PENDING_INIT,
    @SerializedName("remote_url")
    val remoteUrl: String? = null,
    @SerializedName("current_branch")
    val currentBranch: String? = null,
    @SerializedName("last_commit")
    val lastCommit: String? = null,
    @SerializedName("ahead_count")
    val aheadCount: Int = 0,
    @SerializedName("behind_count")
    val behindCount: Int = 0,
    @SerializedName("changed_files_count")
    val changedFilesCount: Int? = null,
    @SerializedName("error")  val error: String? = null,
)
