package com.gitmob.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em

/**
 * A piece of a GitHub description after shortcode parsing.
 *
 * Standard shortcodes are already converted to Unicode in [TextPart]. Custom GitHub-only
 * shortcodes remain image parts so they can be embedded in the same text flow.
 */
internal sealed interface GitHubEmojiTextPart {
    data class TextPart(val value: String) : GitHubEmojiTextPart

    data class CustomEmojiPart(
        val shortcode: String,
        @DrawableRes val drawableRes: Int,
    ) : GitHubEmojiTextPart
}

private val githubEmojiShortcodePattern = Regex(":([A-Za-z0-9_+\\-]+):")

/** Parses a complete string without dropping unknown or malformed shortcode text. */
internal fun parseGitHubEmojiText(text: String): List<GitHubEmojiTextPart> {
    if (text.isEmpty()) return emptyList()

    val parts = mutableListOf<GitHubEmojiTextPart>()
    var textStart = 0

    fun appendText(value: String) {
        if (value.isNotEmpty()) {
            parts += GitHubEmojiTextPart.TextPart(emojizeGitHubText(value))
        }
    }

    githubEmojiShortcodePattern.findAll(text).forEach { match ->
        appendText(text.substring(textStart, match.range.first))
        val shortcode = match.value
        val drawable = githubCustomEmojiDrawable(shortcode)
        if (drawable == null) {
            appendText(shortcode)
        } else {
            parts += GitHubEmojiTextPart.CustomEmojiPart(shortcode, drawable)
        }
        textStart = match.range.last + 1
    }
    appendText(text.substring(textStart))
    return parts
}

/**
 * Renders text that may contain both regular Unicode-convertible GitHub shortcodes and
 * GitHub-only image emoji, such as `:octocat:Github主页 :shipit:`.
 */
@Composable
fun GitHubEmojiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val parts = remember(text) { parseGitHubEmojiText(text) }
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val annotatedText = buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            when (part) {
                is GitHubEmojiTextPart.TextPart -> append(part.value)
                is GitHubEmojiTextPart.CustomEmojiPart -> {
                    val id = "github-custom-emoji-$index"
                    appendInlineContent(id, part.shortcode)
                    inlineContent[id] = InlineTextContent(
                        placeholder = Placeholder(
                            width = 1.em,
                            height = 1.em,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                        children = {
                            Image(
                                painter = painterResource(part.drawableRes),
                                contentDescription = part.shortcode,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                }
            }
        }
    }

    Text(
        text = annotatedText,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
    )
}
