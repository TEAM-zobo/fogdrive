package com.zobo.burung.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class SyncState(
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val autoSync: Boolean = true,
    val error: String? = null,
)

/**
 * Cloud sync of the record across the driver's cars. Pleos documents a Sync SDK for this
 * (SyncDatabase / SyncDocument, permission pleos.car.permission.SYNC_DATA), but it is not published
 * to the Playground maven repository yet. Until it is, a sync packs the backup into an on-device
 * outbox (the payload the server or the SDK will take over) and remembers when it last ran, so the
 * flow and the UI are already in place.
 */
class CloudSync(context: Context, private val repo: ExploreRepository) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val outbox = File(context.filesDir, "sync/outbox.fgbk")

    private val _state = MutableStateFlow(
        SyncState(lastSyncAt = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }, autoSync = prefs.getBoolean(KEY_AUTO, true)),
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        _state.update { it.copy(autoSync = on) }
    }

    suspend fun syncNow() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true, error = null) }
        try {
            repo.exportTo(outbox)
            // The JSON outbox of format 1, replaced by the binary one.
            File(outbox.parentFile, "outbox.json").delete()
            delay(UPLOAD_FEEL_MS)  // stand-in for the upload round trip
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST, now).apply()
            _state.update { it.copy(syncing = false, lastSyncAt = now) }
        } catch (e: Exception) {
            _state.update { it.copy(syncing = false, error = e.message ?: "동기화에 실패했어요") }
        }
    }

    private companion object {
        const val PREFS = "fogdrive_sync"
        const val KEY_LAST = "last_sync_at"
        const val KEY_AUTO = "auto_sync"
        const val UPLOAD_FEEL_MS = 1_200L
    }
}
