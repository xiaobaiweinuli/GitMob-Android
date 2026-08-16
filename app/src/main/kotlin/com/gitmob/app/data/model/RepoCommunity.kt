package com.gitmob.app.data.model

data class RepoContributor(val login: String?, val avatarUrl: String?, val profileUrl: String?, val contributions: Int, val type: String?)
data class RepoLicenseDocument(val name: String, val spdxId: String?, val path: String, val htmlUrl: String?, val content: String)
