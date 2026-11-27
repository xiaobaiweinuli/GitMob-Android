package com.gitmob.app.core.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.DocumentResourceKind
import com.gitmob.app.data.model.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import okhttp3.Dispatcher
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Each viewer owns its requests; closing it cannot cancel other application traffic. */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class MediaPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GHApiClient,
    private val cache: MediaCache,
) {
    suspend fun create(source: MediaSource, epoch: AccountEpoch): Session {
        epoch.ensureCurrent()
        val client = api.mediaClient(epoch)
        val dataSource = cache.dataSourceFactory(OkHttpDataSource.Factory(client), source, epoch)
        epoch.ensureCurrent()
        val dispatcher = client.dispatcher
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(5_000)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val session = Session(player, source, dispatcher, Job(epoch.job))
        return try {
            player.setMediaItem(MediaItem.Builder().setUri(source.url).setMimeType(source.mimeType).build())
            session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    class Session internal constructor(
        val player: ExoPlayer,
        val source: MediaSource,
        private val dispatcher: Dispatcher,
        private val job: Job,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        val isClosed: Boolean get() = closed.get()
        private var activeOwner: Any? = null
        private var active = false
        private var resumeWhenActive = false
        var hasRenderedFrame: Boolean = false
            private set

        init {
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { hasRenderedFrame = true }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) pause()
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                        resumeWhenActive = false
                    }
                }
            })
            job.invokeOnCompletion { release() }
        }

        /** Prepare only the visible video; suspension preserves the user's playback intent. */
        fun setActive(owner: Any, value: Boolean) {
            if (isClosed || (!value && activeOwner !== owner)) return
            // A disposed inline/fullscreen view must not suspend its replacement.
            activeOwner = if (value) owner else null
            if (active == value) return
            active = value
            if (value) {
                if (source.kind == DocumentResourceKind.VIDEO &&
                    player.playbackState == Player.STATE_IDLE && player.playerError == null) player.prepare()
                if (resumeWhenActive && player.playbackState != Player.STATE_ENDED) player.play()
            } else {
                resumeWhenActive = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                player.pause()
            }
        }

        fun togglePlayback() {
            if (isClosed || !active) return
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                player.play()
            }
        }

        fun pause() {
            resumeWhenActive = false
            if (!isClosed) player.pause()
        }
        override fun close() { job.cancel(); release() }
        private fun release() {
            if (!closed.compareAndSet(false, true)) return
            dispatcher.cancelAll()
            if (Looper.myLooper() == player.applicationLooper) player.release()
            else Handler(player.applicationLooper).post { player.release() }
        }
    }
}
