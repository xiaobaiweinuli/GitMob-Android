package com.gitmob.app.data.model

/** GitHub 返回的当前用户标星状态及仓库总星数。 */
data class RepoStarState(
    val viewerHasStarred: Boolean,
    val stargazerCount: Int,
)
