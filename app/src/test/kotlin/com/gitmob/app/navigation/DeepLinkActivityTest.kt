package com.gitmob.app.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeepLinkActivityTest {
    @Test
    fun `Gist详情转发到主界面并复用已有实例`() {
        val activity = launch("https://gist.github.com/std-microblock/abc123")
        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        @Suppress("DEPRECATION")
        val destination = forwarded.getSerializableExtra(EXTRA_DEEP_LINK_DESTINATION)
        assertEquals(DeepLinkDestination.GistDetail("abc123"), destination)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `不支持的路径保留完整URL且只匹配浏览器入口`() {
        val url = "https://gist.github.com/owner/abc123/raw?file=hello.kt#L2"
        val activity = launch(url)
        val browser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, browser.action)
        assertEquals(url, browser.dataString)
        assertTrue(browser.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(Intent.ACTION_MAIN, browser.selector?.action)
        assertTrue(browser.selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
        assertNull(browser.component)
        val manager = activity.packageManager
        @Suppress("DEPRECATION")
        val matches = manager.queryIntentActivities(browser.selector!!, 0)
        assertFalse(matches.any { it.activityInfo.name == DeepLinkActivity::class.java.name })
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `没有浏览器时将错误交给主界面`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).checkActivities(true)
        val activity = launch("https://example.com/unsupported")
        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        @Suppress("DEPRECATION")
        val destination = forwarded.getSerializableExtra(EXTRA_DEEP_LINK_DESTINATION)
        assertEquals(DeepLinkDestination.BrowserUnavailable, destination)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `没有URI时直接结束`() {
        val activity = Robolectric.buildActivity(DeepLinkActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `Manifest声明GitHub和Gist的HTTP与HTTPS入口`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        for (host in listOf("github.com", "gist.github.com")) {
            for (scheme in listOf("http", "https")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://$host/owner/abc123"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                @Suppress("DEPRECATION")
                val matches = application.packageManager.queryIntentActivities(intent, 0)
                assertTrue("$scheme://$host", matches.any { it.activityInfo.name == DeepLinkActivity::class.java.name })
            }
        }
    }

    private fun launch(url: String): DeepLinkActivity = Robolectric.buildActivity(
        DeepLinkActivity::class.java,
        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
    ).create().get()
}
