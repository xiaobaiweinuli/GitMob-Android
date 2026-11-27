package com.gitmob.app.data.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.IntentSender
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import app.cash.turbine.test
import com.gitmob.app.MainActivity
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.data.model.LauncherShortcut
import com.gitmob.app.data.model.ShortcutKind
import com.gitmob.app.data.model.ShortcutPublishResult
import com.gitmob.app.navigation.DeepLinkActivity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherShortcutRepositoryTest {
    private val manager = mockk<ShortcutManager>()
    private val dynamic = linkedMapOf<String, ShortcutInfo>()
    private val pinned = linkedMapOf<String, ShortcutInfo>()
    private lateinit var repository: LauncherShortcutRepository
    private fun item(id: String, title: String = id) = LauncherShortcut(
        "repo:$id", ShortcutKind.REPOSITORY, title, "owner/$id", "https://github.com/owner/$id",
    )

    @Before fun setUp() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? = if (name == Context.SHORTCUT_SERVICE) manager else super.getSystemService(name)
        }
        every { manager.dynamicShortcuts } answers { dynamic.values.toList() }
        every { manager.pinnedShortcuts } answers { pinned.values.toList() }
        every { manager.manifestShortcuts } returns emptyList()
        every { manager.maxShortcutCountPerActivity } returns 2
        every { manager.isRequestPinShortcutSupported } returns true
        every { manager.addDynamicShortcuts(any()) } answers {
            firstArg<List<ShortcutInfo>>().forEach { dynamic[it.id] = it; if (it.id in pinned) pinned[it.id] = it }
            true
        }
        every { manager.setDynamicShortcuts(any()) } answers {
            dynamic.clear()
            firstArg<List<ShortcutInfo>>().forEach { dynamic[it.id] = it; if (it.id in pinned) pinned[it.id] = it }
            true
        }
        every { manager.updateShortcuts(any()) } answers {
            firstArg<List<ShortcutInfo>>().forEach {
                if (it.id in dynamic) dynamic[it.id] = it
                if (it.id in pinned) pinned[it.id] = it
            }
            true
        }
        every { manager.removeDynamicShortcuts(any()) } answers { firstArg<List<String>>().forEach(dynamic::remove) }
        every { manager.requestPinShortcut(any(), any()) } returns true
        repository = LauncherShortcutRepository(context)
    }

    @Test fun repeatedTargetUpdatesOneEntryWithExplicitDeepLink() = runTest {
        assertEquals(ApiResult.Success(ShortcutPublishResult.Saved), repository.add(item("a")))
        repository.add(item("a", "Renamed"))
        assertEquals(1, dynamic.size)
        val published = dynamic.getValue("repo:a")
        assertEquals("Renamed", published.shortLabel)
        assertEquals(MainActivity::class.java.name, published.activity?.className)
        assertEquals(DeepLinkActivity::class.java.name, published.intent!!.component!!.className)
        assertEquals("https://github.com/owner/a", published.intent!!.data.toString())
    }

    @Test fun fullMenuRequiresExplicitReplacementAndKeepsPinnedCopy() = runTest {
        repository.add(item("a")); repository.add(item("b"))
        pinned["repo:a"] = dynamic.getValue("repo:a")
        assertEquals(ApiResult.Success(ShortcutPublishResult.Full), repository.add(item("c")))
        assertEquals(setOf("repo:a", "repo:b"), dynamic.keys)
        assertEquals(ApiResult.Success(ShortcutPublishResult.Saved), repository.add(item("c"), "repo:a"))
        assertEquals(listOf("repo:c", "repo:b"), repository.state.value!!.dynamic.map { it.id })
        assertEquals("repo:a", repository.state.value!!.pinned.single().id)
        verify(exactly = 1) { manager.setDynamicShortcuts(any()) }
        verify(exactly = 0) { manager.removeDynamicShortcuts(any()) }
        verify(exactly = 0) { manager.disableShortcuts(any()) }
    }

    @Test fun removeKeepsPinnedAndRenameUpdatesBothKinds() = runTest {
        repository.add(item("a")); pinned["repo:a"] = dynamic.getValue("repo:a")
        repository.rename("repo:a", "Both")
        assertEquals("Both", dynamic.getValue("repo:a").shortLabel)
        assertEquals("Both", pinned.getValue("repo:a").shortLabel)
        repository.remove("repo:a")
        repository.rename("repo:a", "Desktop only")
        assertTrue(dynamic.isEmpty())
        assertEquals("Desktop only", repository.state.value!!.pinned.single().title)
        verify(exactly = 0) { manager.disableShortcuts(any()) }
    }

    @Test fun reorderRejectsStaleIdsAndStoresNewRanks() = runTest {
        repository.add(item("a")); repository.add(item("b"))
        assertTrue(repository.reorder(listOf("repo:a")) is ApiResult.Failure)
        verify(exactly = 0) { manager.updateShortcuts(any()) }
        assertEquals(ApiResult.Success(Unit), repository.reorder(listOf("repo:b", "repo:a")))
        assertEquals(listOf("repo:b", "repo:a"), repository.state.value!!.dynamic.map { it.id })
    }

    @Test fun failedReplacementDoesNotRemoveExistingShortcuts() = runTest {
        repository.add(item("a")); repository.add(item("b"))
        every { manager.setDynamicShortcuts(any()) } returns false
        val result = repository.add(item("c"), "repo:a")
        assertTrue(result is ApiResult.Failure && result.error is ApiError.UserVisible)
        assertEquals(listOf("repo:a", "repo:b"), repository.state.value!!.dynamic.map { it.id })
        verify(exactly = 0) { manager.removeDynamicShortcuts(any()) }
    }

    @Test fun acceptedPinRequestDoesNotReportSuccessUntilConfirmed() = runTest {
        repository.add(item("a"))
        repository.pinConfirmed.test {
            assertEquals(ApiResult.Success(ShortcutPublishResult.PinRequested), repository.pin(item("a", "New name"), "request"))
            assertEquals("New name", dynamic.getValue("repo:a").shortLabel)
            expectNoEvents()
            repository.confirmPinned("repo:a", "request")
            expectNoEvents()
            pinned["repo:a"] = dynamic.getValue("repo:a")
            repository.confirmPinned("repo:a", "request")
            assertEquals("request", awaitItem())
        }
        verifyOrder { manager.updateShortcuts(any()); manager.requestPinShortcut(any(), any<IntentSender>()) }
    }

    @Test fun pinnedTargetStillRequestsPinForCurrentLauncher() = runTest {
        repository.add(item("a")); pinned["repo:a"] = dynamic.getValue("repo:a")
        assertEquals(ApiResult.Success(ShortcutPublishResult.PinRequested), repository.pin(item("a", "Updated"), "request"))
        assertEquals("Updated", pinned.getValue("repo:a").shortLabel)
        verify(exactly = 1) { manager.requestPinShortcut(any(), any()) }
    }

    @Test fun unavailablePinStillSavesNameWithoutReportingAdded() = runTest {
        repository.add(item("a"))
        every { manager.isRequestPinShortcutSupported } returns false
        assertEquals(ApiResult.Success(ShortcutPublishResult.PinUnavailable), repository.pin(item("a", "Saved"), "request"))
        assertEquals("Saved", repository.state.value!!.dynamic.single().title)
        verify(exactly = 0) { manager.requestPinShortcut(any(), any()) }
    }

    @Test fun staticShortcutsConsumeCapacityForTheLauncherActivity() = runTest {
        repository.add(item("a"))
        every { manager.manifestShortcuts } returns listOf(dynamic.getValue("repo:a"))
        repository.refresh()
        assertEquals(1, repository.state.value!!.capacity)
        assertEquals(ApiResult.Success(ShortcutPublishResult.Full), repository.add(item("b")))
    }
}
