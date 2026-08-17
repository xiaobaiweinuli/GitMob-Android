package com.gitmob.app.core.download

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.core.error.UserVisibleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalDownloadLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcher = ExternalDownloadLauncher(context)

    @Test
    fun `valid HTTPS URL is handed to Android without selecting a package`() {
        val url = "https://release-assets.githubusercontent.com/file.apk?signature=abc"

        launcher.open(url)

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertNull(started.`package`)
        assertNull(started.component)
    }

    @Test
    fun `non HTTPS URL is rejected before launching an intent`() {
        assertThrows(UserVisibleException::class.java) {
            launcher.open("http://example.com/file.apk")
        }
    }

    @Test
    fun `URL containing user info is rejected before launching an intent`() {
        assertThrows(UserVisibleException::class.java) {
            launcher.open("https://token@example.com/file.apk")
        }
    }
}
