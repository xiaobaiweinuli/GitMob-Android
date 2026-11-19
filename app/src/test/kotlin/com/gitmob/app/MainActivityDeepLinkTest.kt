package com.gitmob.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.MutableState
import com.gitmob.app.navigation.DeepLinkDestination
import com.gitmob.app.navigation.EXTRA_DEEP_LINK_DESTINATION
import dagger.hilt.android.internal.managers.ActivityComponentManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainActivityDeepLinkTest {
    private val firstLink = DeepLinkDestination.RepoOverview("tanmoumou252", "skill_toolkit")
    private val secondLink = DeepLinkDestination.RepoOverview("OpenMinis", "ish-pkgs")
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    @After
    fun tearDown() {
        controllers.forEach { it.destroy() }
    }

    @Test
    fun `cold and warm search links carry their query across recreation`() {
        val cold = DeepLinkDestination.IssueList("o", "r", "state:open 发布")
        val warm = DeepLinkDestination.PullRequestList("o", "r", "is:merged")
        val controller = launch(intentFor(cold))
        assertEquals(cold, controller.get().pendingState().value)
        controller.newIntent(intentFor(warm))
        val saved = saveAndDestroy(controller)
        assertEquals(warm, launch(intentFor(cold), parcelCopy(saved)).get().pendingState().value)
    }

    @Test
    fun `first creation reads the incoming deep link`() {
        val activity = launch(intentFor(firstLink)).get()

        assertEquals(firstLink, activity.pendingState().value)
    }

    @Test
    fun `consumed link is not replayed from original intent after process restoration`() {
        val originalIntent = intentFor(firstLink)
        val controller = launch(Intent(originalIntent))
        // 模拟导航消费后的状态；此处验证真实 Activity 的保存恢复，不启动 Compose 页面。
        controller.get().pendingState().value = null
        assertTrue(controller.get().intent.hasExtra(EXTRA_DEEP_LINK_DESTINATION))
        val savedState = saveAndDestroy(controller)

        val restored = launch(Intent(originalIntent), parcelCopy(savedState)).get()

        assertTrue(restored.intent.hasExtra(EXTRA_DEEP_LINK_DESTINATION))
        assertNull(restored.pendingState().value)
    }

    @Test
    fun `unconsumed link survives restoration without an intent extra`() {
        val savedState = saveAndDestroy(launch(intentFor(firstLink)))

        val restored = launch(Intent(), parcelCopy(savedState)).get()

        assertEquals(firstLink, restored.pendingState().value)
    }

    @Test
    fun `latest pending link takes precedence over the original launch intent`() {
        val originalIntent = intentFor(firstLink)
        val controller = launch(Intent(originalIntent))
        controller.newIntent(intentFor(secondLink))
        val savedState = saveAndDestroy(controller)

        val restored = launch(Intent(originalIntent), parcelCopy(savedState)).get()

        assertEquals(secondLink, restored.pendingState().value)
    }

    @Test
    fun `consumed link remains consumed when recreated with the same intent`() {
        val controller = launch(intentFor(firstLink))
        controller.get().pendingState().value = null
        val sameIntent = controller.get().intent
        val savedState = saveAndDestroy(controller)

        val restored = launch(sameIntent, savedState).get()

        assertNull(restored.pendingState().value)
        assertTrue(sameIntent.hasExtra(EXTRA_DEEP_LINK_DESTINATION))
    }

    @Test
    fun `new intents accept the same URL and different URLs after consumed state restoration`() {
        for (nextLink in listOf(firstLink, secondLink)) {
            val controller = launch(intentFor(firstLink))
            controller.get().pendingState().value = null
            val savedState = saveAndDestroy(controller)
            val restored = launch(intentFor(firstLink), parcelCopy(savedState))
            assertNull(restored.get().pendingState().value)

            val newIntent = intentFor(nextLink)
            restored.newIntent(newIntent)

            assertEquals(nextLink, restored.get().pendingState().value)
            assertEquals(newIntent, restored.get().intent)
        }
    }

    private fun launch(intent: Intent, state: Bundle? = null): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        // 仅隔离 Hilt 注入；onCreate/onNewIntent/onSaveInstanceState 均执行生产代码。
        // 不 attach 窗口，避免生命周期状态测试启动网络请求或 Compose 导航。
        val manager = mockk<ActivityComponentManager>(relaxed = true)
        every { manager.generatedComponent() } returns mockk<MainActivity_GeneratedInjector>(relaxed = true)
        ReflectionHelpers.setField(controller.get(), "componentManager", manager)
        controllers += controller
        return controller.create(state)
    }

    private fun saveAndDestroy(controller: ActivityController<MainActivity>): Bundle = Bundle().also {
        controller.saveInstanceState(it).destroy()
        controllers -= controller
    }

    private fun intentFor(destination: DeepLinkDestination): Intent =
        Intent().putExtra(EXTRA_DEEP_LINK_DESTINATION, destination)

    private fun MainActivity.pendingState(): MutableState<DeepLinkDestination?> =
        ReflectionHelpers.getField(this, "pendingDeepLink\$delegate")

    private fun parcelCopy(state: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            state.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel).apply {
                classLoader = DeepLinkDestination::class.java.classLoader
            }
        } finally {
            parcel.recycle()
        }
    }
}
