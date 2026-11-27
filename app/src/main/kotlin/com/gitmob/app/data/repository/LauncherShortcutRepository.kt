package com.gitmob.app.data.repository

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import com.gitmob.app.MainActivity
import com.gitmob.app.R
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.shortcut.ShortcutPinnedReceiver
import com.gitmob.app.data.model.LauncherShortcut
import com.gitmob.app.data.model.LauncherShortcutState
import com.gitmob.app.data.model.ShortcutKind
import com.gitmob.app.data.model.ShortcutPublishResult
import com.gitmob.app.navigation.DeepLinkActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class LauncherShortcutRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
    private val activity = ComponentName(context, MainActivity::class.java)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<LauncherShortcutState?>(null)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _pinConfirmed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pinConfirmed = _pinConfirmed.asSharedFlow()

    suspend fun refresh() = safeCall { withContext(Dispatchers.IO) { mutex.withLock { readState() } } }

    suspend fun add(item: LauncherShortcut, replaceId: String? = null) = change {
        val current = readState()
        val dynamic = current.dynamic
        val existing = dynamic.any { it.id == item.id }
        val full = !existing && dynamic.size >= current.capacity
        if (full && (replaceId == null || dynamic.none { it.id == replaceId })) {
            return@change ShortcutPublishResult.Full
        }
        val updated = item.copy(title = validTitle(item.title))
        if (full) {
            requirePublished(manager.setDynamicShortcuts(dynamic.map {
                build(if (it.id == replaceId) updated.copy(rank = it.rank) else it)
            }))
        } else {
            val rank = dynamic.firstOrNull { it.id == item.id }?.rank ?: dynamic.size
            requirePublished(manager.addDynamicShortcuts(listOf(build(updated.copy(rank = rank)))))
        }
        ShortcutPublishResult.Saved
    }

    suspend fun rename(id: String, title: String) = change {
        val item = requireItem(readState(), id)
        requirePublished(manager.updateShortcuts(listOf(build(item.copy(title = validTitle(title))))))
    }

    suspend fun remove(id: String) = change {
        manager.removeDynamicShortcuts(listOf(id))
    }

    suspend fun reorder(ids: List<String>) = change {
        val dynamic = readState().dynamic
        if (ids.size != dynamic.size || ids.toSet() != dynamic.map { it.id }.toSet()) {
            throw UserVisibleException(R.string.shortcuts_changed)
        }
        val byId = dynamic.associateBy { it.id }
        requirePublished(manager.updateShortcuts(ids.mapIndexed { rank, id -> build(byId.getValue(id).copy(rank = rank)) }))
        if (readState().dynamic.map { it.id } != ids) throw UserVisibleException(R.string.shortcuts_changed)
    }

    suspend fun pin(item: LauncherShortcut, requestId: String) = change {
        val current = readState()
        val updated = item.copy(title = validTitle(item.title))
        // Existing pinned and dynamic copies share the same labels.
        current.shortcuts.firstOrNull { it.id == item.id }?.let {
            requirePublished(manager.updateShortcuts(listOf(build(updated.copy(rank = it.rank)))))
        }
        if (!manager.isRequestPinShortcutSupported) return@change ShortcutPublishResult.PinUnavailable
        val callback = Intent(context, ShortcutPinnedReceiver::class.java).apply {
            data = Uri.Builder().scheme("gitmob-shortcut").authority("pinned")
                .appendPath(item.id).appendPath(requestId).build()
        }
        val sender = PendingIntent.getBroadcast(context, 0, callback,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE).intentSender
        if (manager.requestPinShortcut(build(updated), sender)) ShortcutPublishResult.PinRequested
        else ShortcutPublishResult.PinUnavailable
    }

    suspend fun confirmPinned(id: String, requestId: String) {
        val result = refresh()
        if (result is com.gitmob.app.core.error.ApiResult.Success &&
            result.data.shortcuts.any { it.id == id && it.pinned }) {
            _pinConfirmed.emit(requestId)
        }
    }

    private suspend fun <T> change(block: () -> T) = safeCall {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _busy.value = true
                try { block().also { readState() } } finally { _busy.value = false }
            }
        }
    }

    private fun readState(): LauncherShortcutState {
        val dynamic = manager.dynamicShortcuts
        val pinned = manager.pinnedShortcuts
        val dynamicIds = dynamic.map { it.id }.toSet()
        val pinnedIds = pinned.map { it.id }.toSet()
        return LauncherShortcutState(
            shortcuts = (dynamic + pinned).distinctBy { it.id }.mapNotNull { info ->
                val extras = info.extras ?: return@mapNotNull null
                val kind = ShortcutKind.entries.firstOrNull { it.name == extras.getString("kind") }
                    ?: return@mapNotNull null
                val url = info.intent?.data?.toString() ?: return@mapNotNull null
                LauncherShortcut(info.id, kind, info.shortLabel.toString(),
                    extras.getString("subtitle").orEmpty(), url, info.rank,
                    info.id in dynamicIds, info.id in pinnedIds)
            },
            capacity = (manager.maxShortcutCountPerActivity - manager.manifestShortcuts.count { it.activity == activity }).coerceAtLeast(0),
            pinSupported = manager.isRequestPinShortcutSupported,
        ).also { _state.value = it }
    }

    private fun build(item: LauncherShortcut): ShortcutInfo = ShortcutInfo.Builder(context, item.id)
        .setActivity(activity)
        .setShortLabel(item.title)
        .setLongLabel(item.title)
        .setRank(item.rank)
        .setIcon(icon(item.kind))
        .setIntent(Intent(context, DeepLinkActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(item.url)
        })
        .setExtras(PersistableBundle().apply {
            putString("kind", item.kind.name)
            putString("subtitle", item.subtitle)
        })
        .build()

    private fun icon(kind: ShortcutKind): Icon {
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val drawable = requireNotNull(ContextCompat.getDrawable(context,
            if (kind == ShortcutKind.REPOSITORY) R.drawable.oct_repo_16 else R.drawable.oct_code_16)).mutate()
        drawable.setTint(Color.rgb(32, 33, 36))
        val inset = size / 4
        drawable.setBounds(inset, inset, size - inset, size - inset)
        drawable.draw(canvas)
        return Icon.createWithAdaptiveBitmap(bitmap)
    }

    private fun validTitle(title: String) = title.trim().takeIf { it.isNotBlank() }
        ?: throw UserVisibleException(R.string.shortcuts_name_required)

    private fun requireItem(state: LauncherShortcutState, id: String) = state.shortcuts.firstOrNull { it.id == id }
        ?: throw UserVisibleException(R.string.shortcuts_changed)

    private fun requirePublished(success: Boolean) {
        if (!success) throw UserVisibleException(R.string.shortcuts_rate_limited)
    }
}
