package com.gitmob.app.data.model

import com.gitmob.app.R
import java.net.URI

enum class ProfileField { NAME, BIO, COMPANY, LOCATION, BLOG, EMAIL, TWITTER_USERNAME }

/** 表单值与网络 DTO 分开；服务端 null 在文本框中显示为空、工作机会开关显示为关闭。 */
data class ProfileDraft(
    val name: String = "",
    val bio: String = "",
    val company: String = "",
    val location: String = "",
    val blog: String = "",
    val email: String = "",
    val twitterUsername: String = "",
    val hireable: Boolean = false,
) {
    // shortcut: 简介按 Unicode code point 计数，实测 GitHub 采用其他计数规则时再调整。
    val bioLength: Int get() = bio.codePointCount(0, bio.length)

    fun value(field: ProfileField): String = when (field) {
        ProfileField.NAME -> name
        ProfileField.BIO -> bio
        ProfileField.COMPANY -> company
        ProfileField.LOCATION -> location
        ProfileField.BLOG -> blog
        ProfileField.EMAIL -> email
        ProfileField.TWITTER_USERNAME -> twitterUsername
    }

    fun withValue(field: ProfileField, value: String): ProfileDraft = when (field) {
        ProfileField.NAME -> copy(name = value)
        ProfileField.BIO -> copy(bio = value)
        ProfileField.COMPANY -> copy(company = value)
        ProfileField.LOCATION -> copy(location = value)
        ProfileField.BLOG -> copy(blog = value)
        ProfileField.EMAIL -> copy(email = value)
        ProfileField.TWITTER_USERNAME -> copy(twitterUsername = value)
    }

    fun validationErrors(): Map<ProfileField, Int> = buildMap {
        if (bioLength > 160) put(ProfileField.BIO, R.string.profile_bio_invalid)
        if (email.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) {
            put(ProfileField.EMAIL, R.string.profile_email_invalid)
        }
        if (blog.isNotEmpty() && (blog.any { it.isWhitespace() } || runCatching {
                val uri = URI(blog)
                !(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) || uri.host.isNullOrBlank()
            }.getOrDefault(true))) {
            put(ProfileField.BLOG, R.string.profile_blog_invalid)
        }
        if (twitterUsername.isNotEmpty() && !Regex("^[A-Za-z0-9_]+$").matches(twitterUsername)) {
            put(ProfileField.TWITTER_USERNAME, R.string.profile_twitter_invalid)
        }
    }
}

data class EditableProfile(val user: AuthenticatedUser, val values: ProfileDraft)

fun ViewerProfile.withEditableProfile(profile: EditableProfile): ViewerProfile {
    if (user.id != profile.user.nodeId) return this
    return copy(user = user.copy(
        login = profile.user.login, avatarUrl = profile.user.avatarUrl,
        name = profile.values.name, bio = profile.values.bio, company = profile.values.company,
        location = profile.values.location, websiteUrl = profile.values.blog, email = profile.values.email,
    ))
}

fun ProfileOwner.Person.withEditableProfile(profile: EditableProfile): ProfileOwner.Person {
    if (id != profile.user.nodeId) return this
    return copy(
        login = profile.user.login, avatarUrl = profile.user.avatarUrl,
        name = profile.values.name, bio = profile.values.bio, company = profile.values.company,
        location = profile.values.location, websiteUrl = profile.values.blog, email = profile.values.email,
    )
}

/** GitHub 已写成功不能因本地元信息持久化失败被误报为写入失败。 */
data class ProfileSaveResult(val profile: EditableProfile, val localSyncFailed: Boolean = false)
