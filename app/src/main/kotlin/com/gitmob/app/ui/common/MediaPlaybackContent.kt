package com.gitmob.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import com.gitmob.app.R
import com.gitmob.app.core.media.MediaPlayback
import com.gitmob.app.data.model.DocumentResourceKind
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaPlaybackContent(
    playback: MediaPlayback.Session,
    active: Boolean,
    onError: () -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onSeeking: (Boolean) -> Unit = {},
    zoom: ResourceZoomState? = null,
) {
    val player = playback.player
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentError by rememberUpdatedState(onError)
    val currentSeeking by rememberUpdatedState(onSeeking)
    val video = playback.source.kind == DocumentResourceKind.VIDEO
    val contentZoom = zoom ?: remember(playback) { ResourceZoomState() }
    var playbackState by remember(playback) { mutableIntStateOf(player.playbackState) }
    var playing by remember(playback) { mutableStateOf(player.isPlaying) }
    var playRequested by remember(playback) { mutableStateOf(player.playWhenReady) }
    var seekable by remember(playback) { mutableStateOf(player.isCurrentMediaItemSeekable) }
    var videoSize by remember(playback) { mutableStateOf(player.videoSize) }
    var renderedFrame by remember(playback) { mutableStateOf(playback.hasRenderedFrame) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var controlsVisible by remember(playback) { mutableStateOf(false) }
    var seeking by remember(playback) { mutableStateOf(false) }
    var feedback by remember(playback) { mutableStateOf<Pair<Int, Alignment>?>(null) }
    var feedbackSequence by remember(playback) { mutableIntStateOf(0) }
    val loading = active && (playbackState == Player.STATE_BUFFERING ||
        (video && !renderedFrame && playbackState != Player.STATE_ENDED))
    SideEffect { onLoadingChanged(loading) }

    DisposableEffect(playback) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { currentError() }
            override fun onEvents(player: Player, events: Player.Events) {
                playbackState = player.playbackState
                playing = player.isPlaying
                playRequested = player.playWhenReady
                seekable = player.isCurrentMediaItemSeekable
                videoSize = player.videoSize
                renderedFrame = playback.hasRenderedFrame
            }
        }
        if (!playback.isClosed) player.addListener(listener)
        onDispose { if (!playback.isClosed) player.removeListener(listener) }
    }
    DisposableEffect(playback, lifecycle, active) {
        val owner = Any()
        val observer = LifecycleEventObserver { _, _ ->
            playback.setActive(owner, active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        playback.setActive(owner, active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            playback.setActive(owner, false)
            currentSeeking(false)
        }
    }
    LaunchedEffect(viewport, videoSize, contentZoom) {
        contentZoom.resize(viewport, Size(videoSize.width * videoSize.pixelWidthHeightRatio, videoSize.height.toFloat()))
    }
    LaunchedEffect(active, controlsVisible, playing, seeking) {
        if (active && controlsVisible && playing && !seeking) {
            delay(3_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(active) {
        if (!active) { controlsVisible = false; feedback = null }
    }
    LaunchedEffect(feedbackSequence) {
        delay(700)
        feedback = null
    }

    fun showFeedback(label: Int, alignment: Alignment) {
        feedback = label to alignment
        feedbackSequence++
    }
    fun seek(forward: Boolean): Boolean {
        if (!active || playback.isClosed || !seekable) return false
        if (forward) player.seekForward() else player.seekBack()
        showFeedback(if (forward) R.string.media_seek_forward else R.string.media_seek_back,
            if (forward) Alignment.CenterEnd else Alignment.CenterStart)
        return true
    }
    fun togglePlayback(): Boolean {
        if (!active || playback.isClosed) return false
        val pausing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        playback.togglePlayback()
        showFeedback(if (pausing) R.string.media_pause else R.string.media_play, Alignment.Center)
        return true
    }
    val playLabel = stringResource(if (playbackState == Player.STATE_ENDED) R.string.media_replay
        else if (playRequested) R.string.media_pause else R.string.media_play)
    val backLabel = stringResource(R.string.media_seek_back)
    val forwardLabel = stringResource(R.string.media_seek_forward)
    val controlsLabel = stringResource(R.string.media_toggle_controls)
    val progress: @Composable () -> Unit = {
        ProgressSlider(player, Modifier.fillMaxWidth().pointerInput(playback) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                seeking = true
                currentSeeking(true)
                try {
                    do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                    while (event.changes.any { it.pressed })
                } finally {
                    seeking = false
                    currentSeeking(false)
                }
            }
        })
        PositionAndDurationText(player)
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (video) {
                val gestures = if (active) Modifier.resourceGestures(contentZoom,
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { point ->
                        when {
                            point.x < viewport.width / 3 -> seek(false)
                            point.x > viewport.width * 2 / 3 -> seek(true)
                            else -> togglePlayback()
                        }
                    },
                    accessibilityActions = buildList {
                        add(CustomAccessibilityAction(playLabel, ::togglePlayback))
                        if (seekable) {
                            add(CustomAccessibilityAction(backLabel) { seek(false) })
                            add(CustomAccessibilityAction(forwardLabel) { seek(true) })
                        }
                        add(CustomAccessibilityAction(controlsLabel) { controlsVisible = !controlsVisible; true })
                    },
                ) else Modifier
                Box(Modifier.fillMaxSize().onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                    .then(gestures), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().resourceTransform(contentZoom), contentAlignment = Alignment.Center) {
                        // Frame readiness belongs to the session, including inline/fullscreen transitions.
                        ContentFrame(player, Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, shutter = {})
                    }
                }
                feedback?.let { (label, alignment) ->
                    Box(Modifier.align(alignment).padding(24.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape)) {
                        Text(stringResource(label), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Color.White)
                    }
                }
                if (active && controlsVisible && !playback.isClosed) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                            .padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            progress()
                        }
                    }
                }
            } else {
                Icon(Icons.Default.AudioFile, stringResource(R.string.media_audio), Modifier.size(72.dp))
            }
        }
        if (!video && active && !playback.isClosed) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeekBackButton(player)
                    PlayPauseButton(player)
                    SeekForwardButton(player)
                }
                progress()
            }
        }
    }
}
