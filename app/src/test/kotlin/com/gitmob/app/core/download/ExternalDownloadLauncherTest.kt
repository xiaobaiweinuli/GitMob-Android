package com.gitmob.app.core.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import java.io.IOException
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.network.RawUrlPolicy
import com.gitmob.app.core.preferences.*
import com.gitmob.app.navigation.ExternalUriLauncher
import com.gitmob.app.testutil.NetworkPreferencesFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalDownloadLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = RawAccelerationPreferenceStore(NetworkPreferencesFixture())
    private val launcher = ExternalDownloadLauncher(ExternalUriLauncher(context, preferences, RawUrlPolicy()))

    @Test fun validHttpsUrlIsHandedToAndroidWithoutSelectingAPackage() = runTest {
        val url = "https://release-assets.githubusercontent.com/file.apk?signature=abc"
        launcher.open(url)
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertEquals(Intent.ACTION_MAIN, started.selector?.action)
        assertTrue(started.selector?.hasCategory(Intent.CATEGORY_APP_BROWSER) == true)
        assertNull(started.`package`)
        assertNull(started.component)
        assertNull(started.extras)
        assertNull(started.selector?.extras)
    }

    @Test fun invalidUrlsAreRejectedBeforeLaunching() = runTest {
        for (url in listOf("http://example.com/file.apk", "https://token@example.com/file.apk")) {
            assertTrue(runCatching { launcher.open(url) }.exceptionOrNull() is UserVisibleException)
        }
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    @Test fun noBrowserReportsAUserVisibleError() = runTest {
        val unavailable = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw android.content.ActivityNotFoundException() }
        }
        val failed = ExternalDownloadLauncher(ExternalUriLauncher(unavailable, preferences, RawUrlPolicy()))
        val error = runCatching { failed.open("https://logs.example/run.zip?signature=temporary") }.exceptionOrNull()
        assertEquals(R.string.download_external_app_unavailable, (error as UserVisibleException).messageRes)
    }

    @Test fun onlyUnsignedRawDownloadsUseTheSelectedPrefix() = runTest {
        preferences.update { it.copy(services = listOf(RawAccelerationService("a", "Proxy", "https://proxy.example/")), selectedId = "a", enabled = true) }
        val raw = "https://raw.githubusercontent.com/o/r/main/file.png"
        launcher.open(raw)
        assertEquals("https://proxy.example/$raw", shadowOf(context as Application).nextStartedActivity.dataString)
        launcher.open("$raw?token=temporary")
        assertEquals("$raw?token=temporary", shadowOf(context).nextStartedActivity.dataString)
        preferences.update { it.copy(enabled = false) }
        launcher.open(raw)
        assertEquals(raw, shadowOf(context).nextStartedActivity.dataString)
    }

    @Test fun firstRawDownloadWaitsForSavedSelectionBeforeOpening() = runTest {
        val saved = savedSettings()
        val gate = CompletableDeferred<Preferences>()
        val store = delayedStore(gate)
        val pending = async { launcher(store).open(RAW) }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertNull(store.settings.value)

        gate.complete(saved)
        pending.await()
        assertEquals("https://proxy.example/$RAW", shadowOf(context).nextStartedActivity.dataString)
    }

    @Test fun unrelatedDownloadsDoNotReadOrWaitForRawSettings() = runTest {
        val data = mockk<DataStore<Preferences>>()
        every { data.data } throws AssertionError("Non-raw links must not read settings")
        val store = RawAccelerationPreferenceStore(data)
        val url = "https://gist.github.com/viewer/id/archive/revision.zip"
        launcher(store).open(url)
        assertEquals(url, shadowOf(context as Application).nextStartedActivity.dataString)
        assertNull(store.settings.value)
    }

    @Test fun failedSettingsReadDoesNotLaunchDirectlyAndCanBeRetried() = runTest {
        var fail = true
        val data = mockk<DataStore<Preferences>>()
        every { data.data } returns flow {
            if (fail) throw IOException("Read failed")
            emit(savedSettings())
        }
        val store = RawAccelerationPreferenceStore(data)
        assertTrue(runCatching { launcher(store).open(RAW) }.exceptionOrNull() is IOException)
        assertNull(store.settings.value)
        assertNull(shadowOf(context as Application).nextStartedActivity)
        fail = false
        launcher(store).open(RAW)
        assertEquals("https://proxy.example/$RAW", shadowOf(context).nextStartedActivity.dataString)
    }

    @Test fun cancellationWhileReadingSettingsPreventsLateBrowserLaunch() = runTest {
        val gate = CompletableDeferred<Preferences>()
        val store = delayedStore(gate)
        val pending = async { launcher(store).open(RAW) }
        runCurrent()
        pending.cancelAndJoin()
        gate.complete(savedSettings())
        runCurrent()
        assertNull(store.settings.value)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    @Test fun accountChangeWhileReadingSettingsPreventsLateBrowserLaunch() = runTest {
        val gate = CompletableDeferred<Preferences>()
        val session = AccountSession()
        val pending = async(AccountContext(session.epoch)) { launcher(delayedStore(gate)).open(RAW) }
        runCurrent()
        session.replace(emptyList(), null)
        gate.complete(savedSettings())
        pending.join()
        assertTrue(pending.isCancelled)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    private fun launcher(store: RawAccelerationPreferenceStore) =
        ExternalDownloadLauncher(ExternalUriLauncher(context, store, RawUrlPolicy()))

    private fun delayedStore(gate: CompletableDeferred<Preferences>): RawAccelerationPreferenceStore {
        val data = mockk<DataStore<Preferences>>()
        every { data.data } returns flow { emit(gate.await()) }
        return RawAccelerationPreferenceStore(data)
    }

    private fun savedSettings(): Preferences = preferencesOf(stringPreferencesKey("settings") to Json.encodeToString(
        RawAccelerationSettings(listOf(RawAccelerationService("a", "Proxy", "https://proxy.example/")), "a", true),
    ))

    private companion object { const val RAW = "https://raw.githubusercontent.com/o/r/main/file.png" }
}
