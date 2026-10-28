package com.example.couponvault.sync

import android.util.Log
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class SyncState {
    IDLE,
    SYNCING,
    OFFLINE,
    ERROR
}

data class SyncStatus(
    val state: SyncState = SyncState.IDLE,
    val lastSyncedAt: Long? = null,
    val errorMessage: String? = null,
    val pendingCount: Int = 0
)

@OptIn(FlowPreview::class)
class SyncManager(
    private val repository: CouponRepository,
    private val client: SupabaseClient,
    private val preferences: SupabasePreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val CURSOR_OVERLAP_MS = 2 * 60 * 1000L // 2 minutes overlap guard
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val ISO_FORMAT_SHORT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private val syncMutex = Mutex()
    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var localDebounceJob: Job? = null

    init {
        // Observe unsynced count to update pending status
        repository.unsyncedCount.onEach { count ->
            _syncStatus.value = _syncStatus.value.copy(pendingCount = count)
        }.launchIn(scope)

        // Observe last synced timestamp from preferences
        preferences.lastSyncedFlow.onEach { ts ->
            _syncStatus.value = _syncStatus.value.copy(lastSyncedAt = ts)
        }.launchIn(scope)

        // Listen for local changes with 5s debounce
        localDebounceJob = repository.localDataChanged
            .debounce(5000L)
            .onEach {
                syncInternal(isTriggeredByLocalChange = true)
            }
            .launchIn(scope)
    }

    fun syncNow() {
        scope.launch {
            syncInternal(isTriggeredByLocalChange = false)
        }
    }

    suspend fun performSync(): Boolean = withContext(Dispatchers.IO) {
        syncInternal(isTriggeredByLocalChange = false)
    }

    private suspend fun syncInternal(isTriggeredByLocalChange: Boolean): Boolean {
        // If already syncing, wait or skip
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "Sync already in progress; skipping duplicate trigger")
            return false
        }

        try {
            val token = preferences.getAccessToken()
            if (token.isNullOrBlank()) {
                // Not signed in; sync is idle and local changes stay dirty in Room
                _syncStatus.value = _syncStatus.value.copy(state = SyncState.IDLE, errorMessage = null)
                return false
            }

            _syncStatus.value = _syncStatus.value.copy(state = SyncState.SYNCING, errorMessage = null)

            // Step 1: Push dirty rows in batches of 50
            var hasMoreDirty = true
            while (hasMoreDirty) {
                val dirtyBatch = repository.getDirtyCoupons(limit = 50)
                if (dirtyBatch.isEmpty()) {
                    hasMoreDirty = false
                    break
                }

                val pushArray = JSONArray()
                for (coupon in dirtyBatch) {
                    val obj = JSONObject().apply {
                        put("id", coupon.syncId)
                        put("sync_id", coupon.syncId)
                        if (coupon.code != null) put("code", coupon.code) else put("code", JSONObject.NULL)
                        put("status", coupon.status.name)
                        put("source", coupon.source.name)
                        put("extraction_state", coupon.extractionState.name)
                        put("created_at", coupon.createdAt)
                        put("modified_at", coupon.modifiedAt)
                        if (coupon.deletedAt != null) put("deleted_at", coupon.deletedAt) else put("deleted_at", JSONObject.NULL)
                        if (coupon.claimedBy != null) put("claimed_by", coupon.claimedBy) else put("claimed_by", JSONObject.NULL)
                        if (coupon.claimedAt != null) put("claimed_at", coupon.claimedAt) else put("claimed_at", JSONObject.NULL)
                        if (coupon.remoteImagePath != null) put("remote_image_path", coupon.remoteImagePath) else put("remote_image_path", JSONObject.NULL)
                        if (coupon.imageSha256 != null) put("image_sha256", coupon.imageSha256) else put("image_sha256", JSONObject.NULL)
                        if (coupon.imageVariant != null) put("image_variant", coupon.imageVariant) else put("image_variant", JSONObject.NULL)
                    }
                    pushArray.put(obj)
                }

                val pushResult = client.pushCoupons(pushArray)
                if (pushResult.isFailure) {
                    handleSyncError(pushResult.exceptionOrNull())
                    return false
                }

                val responseArray = pushResult.getOrThrow()
                for (i in 0 until responseArray.length()) {
                    val resp = responseArray.getJSONObject(i)
                    val syncId = if (resp.has("sync_id")) resp.getString("sync_id") else resp.getString("id")
                    val status = if (resp.has("status")) resp.getString("status") else resp.getString("result")
                    val serverUpdatedAtIso = resp.optString("updated_at")
                    val serverUpdatedAt = parseIsoToMillis(serverUpdatedAtIso)

                    when (status) {
                        "applied" -> {
                            val local = repository.getCouponBySyncId(syncId)
                            if (local?.deletedAt != null) {
                                repository.purgeBySyncId(syncId)
                            } else {
                                repository.markClean(syncId, serverUpdatedAt)
                            }
                        }
                        "stale" -> {
                            // Server was newer: let pull step reconcile LWW
                            Log.d(TAG, "Pushed row $syncId was stale on server")
                        }
                        "duplicate" -> {
                            val existingId = resp.optString("existing_id")
                            Log.d(TAG, "Pushed row $syncId was duplicate of $existingId")
                            // Adopt server record: point local record to existing_id
                            val local = repository.getCouponBySyncId(syncId)
                            if (local != null && existingId.isNotBlank()) {
                                val existingRow = repository.getCouponBySyncId(existingId)
                                if (existingRow != null) {
                                    // Merge image if existing row has none
                                    if (existingRow.imagePath.isBlank() && local.imagePath.isNotBlank()) {
                                        repository.updateCoupon(existingRow.copy(imagePath = local.imagePath))
                                    }
                                    repository.purgeBySyncId(syncId)
                                } else {
                                    // Rename local syncId to existingId
                                    repository.saveFromSync(
                                        local.copy(
                                            syncId = existingId,
                                            dirty = false,
                                            serverUpdatedAt = serverUpdatedAt
                                        )
                                    )
                                    repository.purgeBySyncId(syncId)
                                }
                            }
                        }
                    }
                }

                if (dirtyBatch.size < 50) {
                    hasMoreDirty = false
                }
            }

            // Step 2: Pull server changes since cursor - 2 minutes
            val currentCursorIso = preferences.pullCursorFlow.first()
            val queryCursorIso = calculateCursorWithOverlap(currentCursorIso)

            var highestServerUpdatedAtIso: String? = null
            var hasMorePullPages = true

            while (hasMorePullPages) {
                val pullResult = client.pullCoupons(afterIsoTimestamp = queryCursorIso, limit = 500)
                if (pullResult.isFailure) {
                    handleSyncError(pullResult.exceptionOrNull())
                    return false
                }

                val pulledArray = pullResult.getOrThrow()
                for (i in 0 until pulledArray.length()) {
                    val item = pulledArray.getJSONObject(i)
                    val syncId = item.getString("id")
                    val code = if (item.isNull("code")) null else item.getString("code")
                    val statusStr = item.getString("status")
                    val sourceStr = item.getString("source")
                    val extractionStateStr = item.optString("extraction_state", "DONE")
                    val createdAt = item.getLong("created_at")
                    val modifiedAt = item.getLong("modified_at")
                    val deletedAt = if (item.isNull("deleted_at")) null else item.getLong("deleted_at")
                    val claimedBy = if (item.isNull("claimed_by")) null else item.getString("claimed_by")
                    val claimedAt = if (item.isNull("claimed_at")) null else item.getLong("claimed_at")
                    val remoteImagePath = if (item.isNull("image_path")) null else item.getString("image_path")
                    val imageSha256 = if (item.isNull("image_sha256")) null else item.getString("image_sha256")
                    val imageVariant = if (item.isNull("image_variant")) null else item.getString("image_variant")
                    val updatedAtIso = item.getString("updated_at")
                    val serverUpdatedAt = parseIsoToMillis(updatedAtIso)

                    if (highestServerUpdatedAtIso == null || updatedAtIso > highestServerUpdatedAtIso) {
                        highestServerUpdatedAtIso = updatedAtIso
                    }

                    val localCoupon = repository.getCouponBySyncId(syncId)
                    if (localCoupon != null) {
                        if (localCoupon.dirty) {
                            // LWW conflict resolution
                            if (modifiedAt >= localCoupon.modifiedAt) {
                                // Server wins
                                if (deletedAt != null) {
                                    repository.purgeBySyncId(syncId)
                                } else {
                                    repository.saveFromSync(
                                        localCoupon.copy(
                                            code = code,
                                            status = CouponStatus.valueOf(statusStr),
                                            source = CouponSource.valueOf(sourceStr),
                                            extractionState = ExtractionState.valueOf(extractionStateStr),
                                            createdAt = createdAt,
                                            modifiedAt = modifiedAt,
                                            deletedAt = deletedAt,
                                            claimedBy = claimedBy,
                                            claimedAt = claimedAt,
                                            remoteImagePath = remoteImagePath,
                                            imageSha256 = imageSha256,
                                            imageVariant = imageVariant,
                                            serverUpdatedAt = serverUpdatedAt,
                                            dirty = false
                                        )
                                    )
                                }
                            } else {
                                // Local wins: keep dirty, will push next time
                                Log.d(TAG, "Local coupon $syncId is newer than server; retaining local")
                            }
                        } else {
                            // Local is clean, apply server update
                            if (deletedAt != null) {
                                repository.purgeBySyncId(syncId)
                            } else {
                                repository.saveFromSync(
                                    localCoupon.copy(
                                        code = code,
                                        status = CouponStatus.valueOf(statusStr),
                                        source = CouponSource.valueOf(sourceStr),
                                        extractionState = ExtractionState.valueOf(extractionStateStr),
                                        createdAt = createdAt,
                                        modifiedAt = modifiedAt,
                                        deletedAt = deletedAt,
                                        claimedBy = claimedBy,
                                        claimedAt = claimedAt,
                                        remoteImagePath = remoteImagePath,
                                        imageSha256 = imageSha256,
                                        imageVariant = imageVariant,
                                        serverUpdatedAt = serverUpdatedAt,
                                        dirty = false
                                    )
                                )
                            }
                        }
                    } else {
                        // Not present locally
                        if (deletedAt == null) {
                            // Check if an existing local coupon has the same code (offline duplicate adoption)
                            val localWithSameCode = if (!code.isNullOrBlank()) repository.getCouponByCode(code) else null
                            if (localWithSameCode != null) {
                                repository.saveFromSync(
                                    localWithSameCode.copy(
                                        syncId = syncId,
                                        status = CouponStatus.valueOf(statusStr),
                                        source = CouponSource.valueOf(sourceStr),
                                        extractionState = ExtractionState.valueOf(extractionStateStr),
                                        createdAt = createdAt,
                                        modifiedAt = modifiedAt,
                                        claimedBy = claimedBy,
                                        claimedAt = claimedAt,
                                        remoteImagePath = remoteImagePath,
                                        imageSha256 = imageSha256,
                                        imageVariant = imageVariant,
                                        serverUpdatedAt = serverUpdatedAt,
                                        dirty = false
                                    )
                                )
                            } else {
                                // Insert fresh row. Note: local photo will not be present on this device yet,
                                // which the UI handles gracefully by showing "Photo not on this device yet".
                                val newCoupon = Coupon(
                                    syncId = syncId,
                                    code = code,
                                    imagePath = "", // No local photo file yet
                                    status = CouponStatus.valueOf(statusStr),
                                    source = CouponSource.valueOf(sourceStr),
                                    extractionState = ExtractionState.valueOf(extractionStateStr),
                                    createdAt = createdAt,
                                    modifiedAt = modifiedAt,
                                    deletedAt = null,
                                    claimedBy = claimedBy,
                                    claimedAt = claimedAt,
                                    remoteImagePath = remoteImagePath,
                                    imageSha256 = imageSha256,
                                    imageVariant = imageVariant,
                                    serverUpdatedAt = serverUpdatedAt,
                                    dirty = false
                                )
                                repository.saveFromSync(newCoupon)
                            }
                        }
                    }
                }

                if (pulledArray.length() < 500) {
                    hasMorePullPages = false
                }
            }

            // Update pull cursor and timestamp
            if (highestServerUpdatedAtIso != null) {
                preferences.setPullCursor(highestServerUpdatedAtIso)
            }
            val now = System.currentTimeMillis()
            preferences.setLastSynced(now)

            _syncStatus.value = _syncStatus.value.copy(
                state = SyncState.IDLE,
                lastSyncedAt = now,
                errorMessage = null
            )
            return true
        } catch (e: Exception) {
            handleSyncError(e)
            return false
        } finally {
            syncMutex.unlock()
        }
    }

    private fun handleSyncError(e: Throwable?) {
        Log.e(TAG, "Sync error: ${e?.message}", e)
        when (e) {
            is SupabaseError.Network -> {
                _syncStatus.value = _syncStatus.value.copy(
                    state = SyncState.OFFLINE,
                    errorMessage = "Offline. Changes will sync when reconnected."
                )
            }
            is SupabaseError.ConfigurationMissing -> {
                _syncStatus.value = _syncStatus.value.copy(
                    state = SyncState.ERROR,
                    errorMessage = e.message
                )
            }
            else -> {
                _syncStatus.value = _syncStatus.value.copy(
                    state = SyncState.ERROR,
                    errorMessage = e?.message ?: "Sync error"
                )
            }
        }
    }

    private fun parseIsoToMillis(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            ISO_FORMAT.parse(isoString)?.time ?: ISO_FORMAT_SHORT.parse(isoString)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun calculateCursorWithOverlap(cursorIso: String?): String? {
        if (cursorIso.isNullOrBlank()) return null
        return try {
            val date = ISO_FORMAT.parse(cursorIso) ?: ISO_FORMAT_SHORT.parse(cursorIso)
            if (date != null) {
                val shifted = Date(date.time - CURSOR_OVERLAP_MS)
                ISO_FORMAT.format(shifted)
            } else {
                cursorIso
            }
        } catch (_: Exception) {
            cursorIso
        }
    }
}
