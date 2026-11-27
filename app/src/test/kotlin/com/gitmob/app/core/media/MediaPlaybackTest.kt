package com.gitmob.app.core.media

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.data.model.DocumentResourceKind
import com.gitmob.app.data.model.MediaSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import okhttp3.Dispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaPlaybackTest {
    @Test fun switchingAccountReleasesPlaybackAndClosingAgainIsSafe() {
        val accounts = AccountSession()
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.applicationLooper } returns Looper.getMainLooper()
        val playback = MediaPlayback.Session(player, MediaSource("https://example.test/movie.mp4", DocumentResourceKind.VIDEO),
            Dispatcher(), Job(accounts.epoch.job))
        accounts.replace(emptyList(), null)
        assertTrue(playback.isClosed)
        playback.close()
        playback.pause()
        verify(exactly = 1) { player.release() }
        verify(exactly = 0) { player.pause() }
    }

    @Test fun pausingAndClosingOnlyAffectsTheViewersSession() {
        val accounts = AccountSession()
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.applicationLooper } returns Looper.getMainLooper()
        val playback = MediaPlayback.Session(player, MediaSource("https://example.test/track.mp3", DocumentResourceKind.AUDIO),
            Dispatcher(), Job(accounts.epoch.job))
        playback.pause()
        verify(exactly = 1) { player.pause() }
        playback.close()
        assertTrue(accounts.epoch.isCurrent)
        verify(exactly = 1) { player.release() }
    }
}
