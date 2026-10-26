package com.gitmob.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventTransitionState.InProgress
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Keeps ordinary back separate from the optional predictive preview.
 *
 * The entries are already decorated by GitMobNavState; this does not create another back stack or
 * state holder. The scene/state overload leaves back handling to us, so exactly one container
 * handler is registered, before any page-level handlers in NavDisplay's content.
 */
@Composable
internal fun GitMobNavDisplay(
    entries: List<NavEntry<NavKey>>,
    enablePredictiveBack: Boolean,
    onBack: () -> Unit,
    transitionSpec: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    popTransitionSpec: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    modifier: Modifier = Modifier,
) {
    val sceneStrategies = remember { listOf(SinglePaneSceneStrategy<NavKey>()) }
    val sceneState = rememberSceneState(entries, sceneStrategies, onBack = onBack)
    val scene = sceneState.currentScene
    val currentInfo = SceneInfo(scene)
    val backInfo = sceneState.previousScenes.map { SceneInfo(it) }
    val gestureState = rememberNavigationEventState(currentInfo, backInfo)
    // Never register this second state with a handler. It stays Idle while real gestures are
    // handled, so a disabled preview cannot seek, reverse, or compose the previous page on drag.
    val ordinaryState = rememberNavigationEventState(currentInfo, backInfo)
    val gestureInProgress by remember(gestureState) {
        derivedStateOf { gestureState.transitionState is InProgress }
    }
    var previewForGesture by remember { mutableStateOf(enablePredictiveBack) }

    // Latch the setting while idle, not on every progress event. Terminal callbacks also update
    // it so cancel/complete followed by a new gesture in the same frame uses the latest setting.
    SideEffect {
        if (!gestureInProgress) previewForGesture = enablePredictiveBack
    }
    NavigationBackHandler(
        state = gestureState,
        isBackEnabled = scene.previousEntries.isNotEmpty(),
        onBackCancelled = { previewForGesture = enablePredictiveBack },
        onBackCompleted = {
            previewForGesture = enablePredictiveBack
            // Match NavDisplay 1.1.4's scene pop-count calculation, including its stale-enabled
            // behavior. Cancelling never changes the stack; only completion reaches this callback.
            repeat(entries.size - scene.previousEntries.size) { onBack() }
        },
    )

    NavDisplay(
        sceneState = sceneState,
        navigationEventState = if (previewForGesture) gestureState else ordinaryState,
        transitionSpec = transitionSpec,
        popTransitionSpec = popTransitionSpec,
        // Match the template's horizontal parallax, not AndroidX's default scale-out. Nav3
        // still owns seeking and commit/cancel settlement; ordinary navigation stays unchanged.
        predictivePopTransitionSpec = {
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(550, easing = LinearEasing),
            ) togetherWith slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(550, easing = LinearEasing),
            )
        },
        modifier = modifier,
    )
}
