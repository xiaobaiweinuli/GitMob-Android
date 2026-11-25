package com.gitmob.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Draw-only feedback keeps row measurement and sibling placement stable. */
@Composable
private fun Modifier.pressFeedback(
    source: MutableInteractionSource,
    enabled: Boolean,
    pressedScale: Float = 0.985f,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "Container press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun Modifier.pressClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return pressFeedback(source, enabled).clickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}

@Composable
fun Modifier.pressToggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return pressFeedback(source, enabled).toggleable(
        value = value,
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onValueChange = onValueChange,
    )
}

@Composable
fun Modifier.pressSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return pressFeedback(source, enabled).selectable(
        selected = selected,
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = modifier.pressFeedback(source, enabled),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun PressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.pressFeedback(source, enabled),
        enabled = enabled,
        shape = shape,
        color = color,
        contentColor = contentColor,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun ActionFloatingButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = icon,
        text = text,
        modifier = modifier.pressFeedback(source, enabled = true, pressedScale = 0.96f),
        interactionSource = source,
    )
}

/** Uses Material shape motion while keeping icon content at its original size. */
@Composable
fun ActionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}

/** A discrete content key prevents counter updates from restarting the transition. */
@Composable
fun <T> ActionStateContent(
    targetState: T,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform { _, _ -> motion.fastSpatialSpec() })
        },
        contentKey = contentKey,
        label = "Action state",
    ) { state -> content(state) }
}

@Composable
fun ActionProgressIcon(
    busy: Boolean,
    modifier: Modifier = Modifier,
    busyDescription: String? = null,
    content: @Composable () -> Unit,
) {
    Crossfade(
        targetState = busy,
        modifier = modifier.size(24.dp).semantics {
            if (busy && busyDescription != null) contentDescription = busyDescription
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "Action progress",
    ) { inProgress ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            } else {
                content()
            }
        }
    }
}

@Composable
fun ActionButtonContent(
    busy: Boolean,
    label: String,
    busyLabel: String = label,
    icon: ImageVector? = null,
) {
    ActionStateContent(busy) { inProgress ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(if (inProgress) busyLabel else label)
        }
    }
}

/** Follow and star actions keep one click target while their appearance changes. */
@Composable
fun StateActionButton(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    selectedLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = selected,
    icon: ImageVector? = null,
    selectedIcon: ImageVector? = icon,
    busy: Boolean = false,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (filled) colors.primary else Color.Transparent,
        animationSpec = motion.fastEffectsSpec(),
        label = "Action container",
    )
    val foreground by animateColorAsState(
        targetValue = if (filled) colors.onPrimary else colors.primary,
        animationSpec = motion.fastEffectsSpec(),
        label = "Action foreground",
    )
    val outline by animateColorAsState(
        targetValue = if (filled) Color.Transparent else colors.outline,
        animationSpec = motion.fastEffectsSpec(),
        label = "Action outline",
    )
    OutlinedButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled && !busy,
        contentPadding = ButtonDefaults.ContentPadding,
        border = BorderStroke(1.dp, outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = container, contentColor = foreground),
    ) {
        ActionStateContent(selected to busy) { (active, inProgress) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inProgress) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                } else {
                    (if (active) selectedIcon else icon)?.let {
                        Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Text(if (active) selectedLabel else label)
            }
        }
    }
}

/** State changes fade between glyphs without moving the surrounding toolbar. */
@Composable
fun ActionStateIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Crossfade(
        targetState = imageVector,
        modifier = modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "Action icon state",
    ) { vector ->
        Icon(vector, contentDescription = null, tint = tint)
    }
}
