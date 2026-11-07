package com.gitmob.app.data.model

/** The ref is kept separate from the path, including branch names containing '/'. */
data class RepoDocumentContext(
    val owner: String,
    val name: String,
    val ref: String,
    val filePath: String,
)
