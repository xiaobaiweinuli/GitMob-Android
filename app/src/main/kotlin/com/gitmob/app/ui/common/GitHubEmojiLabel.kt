package com.gitmob.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle

/** Renders standard GitHub shortcodes and GitHub-only image emoji consistently. */
@Composable
fun GitHubEmojiLabel(
    emoji: String?,
    text: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    contentDescription: String? = null,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val emojiValue = emoji?.trim().orEmpty()
    val customEmoji: Int? = emojiValue.takeIf(String::isNotBlank)?.let(::githubCustomEmojiDrawable)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (customEmoji != null) {
            GitHubCustomEmojiImage(customEmoji, iconSize, contentDescription)
        } else if (emojiValue.isNotBlank()) {
            Text(emojizeGitHubText(emojiValue), style = style, color = color)
        }
        Text(text, style = style, color = color)
    }
}

@Composable
private fun GitHubCustomEmojiImage(
    @DrawableRes drawable: Int,
    iconSize: Dp,
    contentDescription: String?,
) {
    Image(
        painter = painterResource(drawable),
        contentDescription = contentDescription,
        modifier = Modifier.size(iconSize),
    )
}
