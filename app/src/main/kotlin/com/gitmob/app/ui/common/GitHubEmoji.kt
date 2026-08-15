package com.gitmob.app.ui.common

import androidx.annotation.DrawableRes
import com.gitmob.app.R
import com.vdurmont.emoji.EmojiParser
import java.util.Locale

/** Converts standard GitHub-style shortcodes such as :dart: to Unicode emoji. */
fun emojizeGitHubText(text: String): String = EmojiParser.parseToUnicode(text)

/** Resolves the image-only emoji that GitHub adds on top of the Unicode emoji set. */
@DrawableRes
fun githubCustomEmojiDrawable(alias: String): Int? = customEmojiDrawables[normalizeEmojiAlias(alias)]

private fun normalizeEmojiAlias(alias: String): String = alias
    .trim()
    .removePrefix(":")
    .removeSuffix(":")
    .lowercase(Locale.ROOT)

private val customEmojiDrawables = mapOf(
    "accessibility" to R.drawable.github_emoji_accessibility,
    "atom" to R.drawable.github_emoji_atom,
    "basecamp" to R.drawable.github_emoji_basecamp,
    "basecampy" to R.drawable.github_emoji_basecampy,
    "bowtie" to R.drawable.github_emoji_bowtie,
    "copilot" to R.drawable.github_emoji_copilot,
    "dependabot" to R.drawable.github_emoji_dependabot,
    "electron" to R.drawable.github_emoji_electron,
    "feelsgood" to R.drawable.github_emoji_feelsgood,
    "finnadie" to R.drawable.github_emoji_finnadie,
    "fishsticks" to R.drawable.github_emoji_fishsticks,
    "goberserk" to R.drawable.github_emoji_goberserk,
    "godmode" to R.drawable.github_emoji_godmode,
    "hurtrealbad" to R.drawable.github_emoji_hurtrealbad,
    "neckbeard" to R.drawable.github_emoji_neckbeard,
    "octocat" to R.drawable.github_emoji_octocat,
    "rage1" to R.drawable.github_emoji_rage1,
    "rage2" to R.drawable.github_emoji_rage2,
    "rage3" to R.drawable.github_emoji_rage3,
    "rage4" to R.drawable.github_emoji_rage4,
    "shipit" to R.drawable.github_emoji_shipit,
    "suspect" to R.drawable.github_emoji_suspect,
    "trollface" to R.drawable.github_emoji_trollface,
)
