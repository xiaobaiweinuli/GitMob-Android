package com.gitmob.app.data.model

data class ConversationEditSummary(
    val includesCreatedEdit: Boolean = false,
    val lastEditedAt: String? = null,
    val editor: SimpleUser? = null,
)

data class ConversationEdit(
    val id: String,
    val editedAt: String,
    val editor: SimpleUser? = null,
    val diff: String? = null,
    val deletedAt: String? = null,
    val deletedBy: SimpleUser? = null,
)

data class ConversationEditPage(
    val items: List<ConversationEdit>,
    val hasNextPage: Boolean,
    val endCursor: String?,
    val totalCount: Int,
)
