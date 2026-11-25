package com.gitmob.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchTitle(expanded: Boolean, content: @Composable (Boolean) -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = expanded,
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) +
                slideInHorizontally(motion.defaultSpatialSpec()) { it / 12 }) togetherWith
                (fadeOut(motion.fastEffectsSpec()) +
                    slideOutHorizontally(motion.fastSpatialSpec()) { -it / 12 }) using
                SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() }
        },
        label = "Search title",
    ) { content(it) }
}

@Composable
fun RowScope.SearchActions(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + expandHorizontally(motion.defaultSpatialSpec(), expandFrom = Alignment.End),
        exit = fadeOut(motion.fastEffectsSpec()) + shrinkHorizontally(motion.fastSpatialSpec(), shrinkTowards = Alignment.End),
    ) { content() }
}
