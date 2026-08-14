package com.gitmob.app.ui.home

import androidx.annotation.DrawableRes
import com.gitmob.app.R

/**
 * 对应 GraphQL SocialAccountProvider 枚举的完整取值（已用 introspection 核实，
 * 见 assets/github_graphql_schema.json）：GENERIC/FACEBOOK/HOMETOWN/INSTAGRAM/
 * LINKEDIN/MASTODON/REDDIT/THREADS/TWITCH/TWITTER/YOUTUBE/BLUESKY/NPM。
 *
 * 图标资源来自品牌 Logo 矢量图（res/drawable/ic_social_*.xml），非 Material Icons
 * 近似占位。TWITTER 对应的图标文件名是 ic_social_x（品牌已改名 X），公开 GraphQL
 * API 的枚举值本身仍叫 TWITTER，两者不是同一套命名，映射时不要搞反。
 *
 * GENERIC/THREADS/NPM/HOMETOWN 没有对应的品牌图标资源，返回 null，
 * 由调用方使用 Material Link 图标，避免把通用链接误显示成信封。
 */
@DrawableRes
fun socialProviderIconRes(provider: String): Int? = when (provider.uppercase()) {
    "TWITTER" -> R.drawable.ic_social_x
    "MASTODON" -> R.drawable.ic_social_mastodon
    "LINKEDIN" -> R.drawable.ic_social_linkedin
    "REDDIT" -> R.drawable.ic_social_reddit
    "INSTAGRAM" -> R.drawable.ic_social_instagram
    "FACEBOOK" -> R.drawable.ic_social_facebook
    "TWITCH" -> R.drawable.ic_social_twitch
    "BLUESKY" -> R.drawable.ic_social_bluesky
    "YOUTUBE" -> R.drawable.ic_social_youtube
    else -> null // GENERIC/HOMETOWN/THREADS/NPM/未知 provider
}
