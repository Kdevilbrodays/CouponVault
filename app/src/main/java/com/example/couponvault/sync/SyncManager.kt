package com.example.couponvault.sync

import android.content.Context
import android.util.Log
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import com.example.couponvault.sync.realtime.RealtimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

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
    val pendingCount: Int = 0,
    val skippedCount: Int = 0
)

data class ReconcileSummary(
    val removedCount: Int,
    val addedCount: Int
)

@OptIn(FlowPreview::class)
class SyncManager(
    private val repository: CouponRepository,
    private val client: SupabaseClient,
    private val preferences: SupabasePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val queueManager: CouponQueueManager? = null,
    private val context: Context? = null
) {
    val photoDownloadManager: PhotoDownloadManager? = context?.let { PhotoDownloadManager(it, repository, client) }

    companion object {
        private const val TAG = "SyncManager"
        private const val CURSOR_OVERLAP_MS = 2 * 60 * 1000L // 2 minutes overlap guard
        private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L

        fun parseIsoToMillis(isoString: String?): Long {
            if (isoString.isNullOrBlank()) {
                throw IllegalArgumentException("Blank or null ISO timestamp cannot be parsed")
            }
            val clean = isoString.trim().removeSurrounding("\"").replace(' ', 'T')
            return try {
                OffsetDateTime.parse(clean).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    Instant.parse(clean).toEpochMilli()
                } catch (e: Exception) {
                    clean.toLongOrNull() ?: throw IllegalArgumentException("Unparsable ISO timestamp: '$isoString'", e)
                }
            }
        }

        /** Reads a timestamp field that may be an ISO-8601 string (Postgres timestamptz) or epoch millis. */
        fun tsMillis(obj: org.json.JSONObject, key: String): Long {
            val v = obj.get(key)
            return if (v is Number) v.toLong() else parseIsoToMillis(v.toString())
        }

        /** Formats epoch milliseconds to an ISO-8601 UTC string (e.g. 2026-10-28T17:24:11.123Z). */
        fun formatMillisToIso(millis: Long): String {
            return Instant.ofEpochMilli(millis).toString()
        }
    }

    private val syncMutex = Mutex()
    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _realtimeChangeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val realtimeManager = RealtimeManager(
        preferences = preferences,
        scope = scope,
        onRealtimeChange = {
            _realtimeChangeSignal.tryEmit(Unit)
        },
        onConnectOrReconnect = {
            scope.launch {
                syncInternal(isTriggeredByLocalChange = false)
            }
        }
    )

    val isRealtimeConnected: StateFlow<Boolean> = realtimeManager.isConnected

    init {
        // Observe unsynced count to update pending status
        repository.unsyncedCount.onEach { count ->
            _syncStatus.value = _syncStatus.value.copy(pendingCount = count)
        }.launchIn(scope)

        // Observe last synced timestamp from preferences
        preferences.lastSyncedFlow.onEach { ts ->
            _syncStatus.value = _syncStatus.value.copy(lastSyncedAt = ts)
        }.launchIn(scope)

        // 1. Prompt pushing: 1s debounce for local changes (coalesces single edits)
        repository.localDataChanged
            .debounce(1000L)
            .onEach {
                syncInternal(isTriggeredByLocalChange = true)
            }
            .launchIn(scope)

        // Immediate push on bulk actions or completed background OCR extractions
        repository.immediatePushRequested
            .onEach {
                syncInternal(isTriggeredByLocalChange = true)
            }
            .launchIn(scope)

        // 2. Realtime change: debounced 300 ms, then runs cursor-based pull
        _realtimeChangeSignal
            .debounce(300L)
            .onEach {
                syncInternal(isTriggeredByLocalChange = false)
            }
            .launchIn(scope)
    }

    fun onAppForegrounded() {
        realtimeManager.setForeground(true)
        scope.launch {
            checkAndRunAutoReconcile()
        }
    }

    fun onAppBackgrounded() {
        realtimeManager.setForeground(false)
    }

    fun setOverlayRunning(running: Boolean) {
        realtimeManager.setOverlayRunning(running)
    }

    fun pushNowImmediately() {
        scope.launch {
            syncInternal(isTriggeredByLocalChange = true)
        }
    }

    fun syncNow() {
        scope.launch {
            syncInternal(isTriggeredByLocalChange = false)
        }
    }

    /**
     * Parses and applies a single pulled remote row into local storage.
     * Returns true if successfully processed, or false if the row was invalid/skipped.
     */
    suspend fun applyPulledRow(item: JSONObject): Boolean {
        val rowId = item.optString("id", "unknown")
        return try {
            val syncId = item.getString("id")
            val code = if (item.isNull("code")) null else item.getString("code")
            val statusStr = item.getString("status")
            val sourceStr = item.getString("source")
            val extractionStateStr = item.optString("extraction_state", "DONE")
            val createdAt = tsMillis(item, "created_at")
            val modifiedAt = tsMillis(item, "modified_at")
            val redeemedAt = if (item.isNull("redeemed_at")) null else tsMillis(item, "redeemed_at")
            val deletedAt = if (item.isNull("deleted_at")) null else tsMillis(item, "deleted_at")
            val claimedBy = if (item.isNull("claimed_by")) null else item.getString("claimed_by")
            val claimedAt = if (item.isNull("claimed_at")) null else tsMillis(item, "claimed_at")
            val remoteImagePath = if (item.isNull("image_path")) null else item.getString("image_path")
            val imageSha256 = if (item.isNull("image_sha256")) null else item.getString("image_sha256")
            val imageVariant = if (item.isNull("image_variant")) null else item.getString("image_variant")
            val updatedAtIso = item.getString("updated_at")
            val serverUpdatedAt = parseIsoToMillis(updatedAtIso)

            val localCoupon = repository.getCouponBySyncId(syncId)
            if (localCoupon != null) {
                if (localCoupon.dirty) {
                    // LWW conflict resolution
                    if (modifiedAt >= localCoupon.modifiedAt) {
                        // Server wins
                        if (deletedAt != null) {
                            queueManager?.onCouponRemovedOrRedeemed(localCoupon.id)
                            repository.purgeBySyncId(syncId)
                        } else {
                            if (statusStr == CouponStatus.REDEEMED.name) {
                                queueManager?.onCouponRemovedOrRedeemed(localCoupon.id)
                            }
                            repository.saveFromSync(
                                localCoupon.copy(
                                    code = code,
                                    status = CouponStatus.valueOf(statusStr),
                                    source = CouponSource.valueOf(sourceStr),
                                    extractionState = ExtractionState.valueOf(extractionStateStr),
                                    createdAt = createdAt,
                                    modifiedAt = modifiedAt,
                                    redeemedAt = redeemedAt,
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
                        Log.d(TAG, "Local coupon $syncId is newer than server; retaining local")
                    }
                } else {
                    // Local is clean, apply server update
                    if (deletedAt != null) {
                        queueManager?.onCouponRemovedOrRedeemed(localCoupon.id)
                        repository.purgeBySyncId(syncId)
                    } else {
                        if (statusStr == CouponStatus.REDEEMED.name) {
                            queueManager?.onCouponRemovedOrRedeemed(localCoupon.id)
                        }
                        repository.saveFromSync(
                            localCoupon.copy(
                                code = code,
                                status = CouponStatus.valueOf(statusStr),
                                source = CouponSource.valueOf(sourceStr),
                                extractionState = ExtractionState.valueOf(extractionStateStr),
                                createdAt = createdAt,
                                modifiedAt = modifiedAt,
                                redeemedAt = redeemedAt,
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
                                redeemedAt = redeemedAt,
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
                        val newCoupon = Coupon(
                            syncId = syncId,
                            code = code,
                            imagePath = "",
                            status = CouponStatus.valueOf(statusStr),
                            source = CouponSource.valueOf(sourceStr),
                            extractionState = ExtractionState.valueOf(extractionStateStr),
                            createdAt = createdAt,
                            modifiedAt = modifiedAt,
                            redeemedAt = redeemedAt,
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "Skipping invalid remote row $rowId: ${e.message}", e)
            false
        }
    }

    suspend fun performSync(): Boolean = withContext(Dispatchers.IO) {
        syncInternal(isTriggeredByLocalChange = false)
    }

    private suspend fun syncInternal(isTriggeredByLocalChange: Boolean): Boolean {
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "Sync already in progress; skipping duplicate trigger")
            return false
        }

        try {
            val token = preferences.getAccessToken()
            if (token.isNullOrBlank()) {
                _syncStatus.value = _syncStatus.value.copy(state = SyncState.IDLE, errorMessage = null)
                return false
            }

            _syncStatus.value = _syncStatus.value.copy(state = SyncState.SYNCING, errorMessage = null)

            // Step 0: Measure clock offset via server_now() RPC
            val clockOffsetResult = client.fetchServerNow()
            if (clockOffsetResult.isSuccess) {
                val offset = clockOffsetResult.getOrThrow()
                preferences.setClockOffset(offset)
            }

            // Step 1: Push dirty rows in batches of 50
            val pushSuccess = pushDirtyCouponsInternal()
            if (!pushSuccess) return false

            // Step 2: Pull server changes since cursor - 2 minutes
            val currentCursorIso = preferences.pullCursorFlow.first()
            val queryCursorIso = calculateCursorWithOverlap(currentCursorIso)

            var totalSkipped = 0
            var lastSkippedError: String? = null
            var hasMorePullPages = true

            while (hasMorePullPages) {
                val pullResult = client.pullCoupons(afterIsoTimestamp = queryCursorIso, limit = 500)
                if (pullResult.isFailure) {
                    handleSyncError(pullResult.exceptionOrNull())
                    return false
                }

                val pulledArray = pullResult.getOrThrow()
                var pageHighestUpdatedAtIso: String? = null
                var pageHighestUpdatedAtMillis: Long? = null

                for (i in 0 until pulledArray.length()) {
                    val item = pulledArray.getJSONObject(i)
                    val rowId = item.optString("id", "unknown")
                    val applied = applyPulledRow(item)
                    if (applied) {
                        try {
                            val updatedAtIso = item.getString("updated_at")
                            val serverUpdatedAt = parseIsoToMillis(updatedAtIso)
                            if (pageHighestUpdatedAtMillis == null || serverUpdatedAt > pageHighestUpdatedAtMillis) {
                                pageHighestUpdatedAtMillis = serverUpdatedAt
                                pageHighestUpdatedAtIso = updatedAtIso
                            }
                        } catch (_: Exception) {}
                    } else {
                        totalSkipped++
                        lastSkippedError = "Row $rowId skipped due to invalid data"
                    }
                }

                // Only advance the pull cursor after the page has been applied
                if (pageHighestUpdatedAtIso != null) {
                    preferences.setPullCursor(pageHighestUpdatedAtIso)
                }

                if (pulledArray.length() < 500) {
                    hasMorePullPages = false
                }
            }

            val now = System.currentTimeMillis()
            preferences.setLastSynced(now)
            preferences.setLastPullTime(now)
            if (lastSkippedError != null) {
                preferences.setLastError(lastSkippedError)
            } else {
                preferences.setLastError(null)
            }

            _syncStatus.value = _syncStatus.value.copy(
                state = SyncState.IDLE,
                lastSyncedAt = now,
                skippedCount = totalSkipped,
                errorMessage = lastSkippedError
            )

            // Trigger photo upload for synced rows if photo sync is enabled
            if (context != null) {
                try {
                    val photoSyncEnabled = preferences.photoSyncEnabledFlow.first()
                    if (photoSyncEnabled) {
                        val needsUpload = repository.getCouponsNeedingPhotoUpload().isNotEmpty()
                        if (needsUpload) {
                            val wifiOnly = preferences.photoWifiOnlyFlow.first()
                            PhotoUploadWorker.enqueue(context, wifiOnly)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Photo upload worker check skipped: ${t.message}")
                }
            }

            return true
        } catch (e: Exception) {
            handleSyncError(e)
            return false
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun pushDirtyCouponsInternal(): Boolean {
        var hasMoreDirty = true
        var pushedAny = false

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
                    put("created_at", formatMillisToIso(coupon.createdAt))
                    put("modified_at", formatMillisToIso(coupon.modifiedAt))
                    if (coupon.redeemedAt != null) put("redeemed_at", formatMillisToIso(coupon.redeemedAt)) else put("redeemed_at", JSONObject.NULL)
                    if (coupon.deletedAt != null) put("deleted_at", formatMillisToIso(coupon.deletedAt)) else put("deleted_at", JSONObject.NULL)
                    if (coupon.claimedBy != null) put("claimed_by", coupon.claimedBy) else put("claimed_by", JSONObject.NULL)
                    if (coupon.claimedAt != null) put("claimed_at", formatMillisToIso(coupon.claimedAt)) else put("claimed_at", JSONObject.NULL)
                    if (coupon.remoteImagePath != null) {
                        put("image_path", coupon.remoteImagePath)
                        put("remote_image_path", coupon.remoteImagePath)
                    } else {
                        put("image_path", JSONObject.NULL)
                        put("remote_image_path", JSONObject.NULL)
                    }
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

            pushedAny = true
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
                            val uid = preferences.userIdFlow.first()
                            if (uid != null) {
                                scope.launch(Dispatchers.IO) {
                                    client.deletePhoto(uid, syncId)
                                }
                            }
                            repository.purgeBySyncId(syncId)
                        } else {
                            repository.markClean(syncId, serverUpdatedAt)
                        }
                    }
                    "stale" -> {
                        Log.d(TAG, "Pushed row $syncId was stale on server")
                    }
                    "duplicate" -> {
                        val existingId = resp.optString("existing_id")
                        val local = repository.getCouponBySyncId(syncId)
                        if (local != null && existingId.isNotBlank()) {
                            val existingRow = repository.getCouponBySyncId(existingId)
                            if (existingRow != null) {
                                if (existingRow.imagePath.isBlank() && local.imagePath.isNotBlank()) {
                                    repository.updateCoupon(existingRow.copy(imagePath = local.imagePath))
                                }
                                repository.purgeBySyncId(syncId)
                            } else {
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

        if (pushedAny) {
            preferences.setLastPushTime(System.currentTimeMillis())
        }
        return true
    }

    suspend fun performFullReconcile(): Result<ReconcileSummary> = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "Sync or reconcile already running")
            return@withContext Result.failure(Exception("Sync already in progress"))
        }

        try {
            val token = preferences.getAccessToken()
            if (token.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Not signed in"))
            }

            _syncStatus.value = _syncStatus.value.copy(state = SyncState.SYNCING, errorMessage = null)

            // Measure clock offset
            val offsetResult = client.fetchServerNow()
            if (offsetResult.isSuccess) {
                preferences.setClockOffset(offsetResult.getOrThrow())
            }

            // 1. Push any local dirty rows first
            pushDirtyCouponsInternal()

            // 2. Fetch all remote rows
            val allRemoteResult = client.fetchAllCouponsForReconcile()
            if (allRemoteResult.isFailure) {
                val err = allRemoteResult.exceptionOrNull()
                handleSyncError(err)
                return@withContext Result.failure(err ?: Exception("Failed to fetch remote rows"))
            }

            val remoteArray = allRemoteResult.getOrThrow()
            val remoteMap = mutableMapOf<String, JSONObject>()
            for (i in 0 until remoteArray.length()) {
                val obj = remoteArray.getJSONObject(i)
                val id = obj.getString("id")
                remoteMap[id] = obj
            }

            var removedCount = 0
            var addedCount = 0

            // 3. For each local row with serverUpdatedAt != null and dirty = false that is missing remotely or tombstoned remotely, remove it locally.
            val allLocal = repository.getAllCouponsIncludingTombstones()
            for (local in allLocal) {
                if (local.serverUpdatedAt != null && !local.dirty) {
                    val remoteObj = remoteMap[local.syncId]
                    val remoteDeletedAt = try {
                        if (remoteObj != null && !remoteObj.isNull("deleted_at")) {
                            tsMillis(remoteObj, "deleted_at")
                        } else null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing deleted_at for row ${local.syncId}: ${e.message}")
                        null
                    }

                    if (remoteObj == null || remoteDeletedAt != null) {
                        queueManager?.onCouponRemovedOrRedeemed(local.id)
                        repository.purgeBySyncId(local.syncId)
                        removedCount++
                    }
                }
            }

            var totalSkipped = 0
            var lastSkippedError: String? = null

            // 4. Insert remote rows missing locally
            for ((syncId, remoteObj) in remoteMap) {
                try {
                    val deletedAt = if (remoteObj.isNull("deleted_at")) null else tsMillis(remoteObj, "deleted_at")
                    if (deletedAt != null) {
                        val local = repository.getCouponBySyncId(syncId)
                        if (local != null && !local.dirty) {
                            queueManager?.onCouponRemovedOrRedeemed(local.id)
                            repository.purgeBySyncId(syncId)
                        }
                        continue
                    }

                    val local = repository.getCouponBySyncId(syncId)
                    val code = if (remoteObj.isNull("code")) null else remoteObj.getString("code")
                    val statusStr = remoteObj.getString("status")
                    val sourceStr = remoteObj.getString("source")
                    val extractionStateStr = remoteObj.optString("extraction_state", "DONE")
                    val createdAt = tsMillis(remoteObj, "created_at")
                    val modifiedAt = tsMillis(remoteObj, "modified_at")
                    val redeemedAt = if (remoteObj.isNull("redeemed_at")) null else tsMillis(remoteObj, "redeemed_at")
                    val claimedBy = if (remoteObj.isNull("claimed_by")) null else remoteObj.getString("claimed_by")
                    val claimedAt = if (remoteObj.isNull("claimed_at")) null else tsMillis(remoteObj, "claimed_at")
                    val remoteImagePath = if (remoteObj.isNull("image_path")) null else remoteObj.getString("image_path")
                    val imageSha256 = if (remoteObj.isNull("image_sha256")) null else remoteObj.getString("image_sha256")
                    val imageVariant = if (remoteObj.isNull("image_variant")) null else remoteObj.getString("image_variant")
                    val updatedAtIso = remoteObj.getString("updated_at")
                    val serverUpdatedAt = parseIsoToMillis(updatedAtIso)

                    if (local == null) {
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
                                    redeemedAt = redeemedAt,
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
                            val newCoupon = Coupon(
                                syncId = syncId,
                                code = code,
                                imagePath = "",
                                status = CouponStatus.valueOf(statusStr),
                                source = CouponSource.valueOf(sourceStr),
                                extractionState = ExtractionState.valueOf(extractionStateStr),
                                createdAt = createdAt,
                                modifiedAt = modifiedAt,
                                redeemedAt = redeemedAt,
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
                            addedCount++
                        }
                    } else if (!local.dirty) {
                        repository.saveFromSync(
                            local.copy(
                                code = code,
                                status = CouponStatus.valueOf(statusStr),
                                source = CouponSource.valueOf(sourceStr),
                                extractionState = ExtractionState.valueOf(extractionStateStr),
                                createdAt = createdAt,
                                modifiedAt = modifiedAt,
                                redeemedAt = redeemedAt,
                                deletedAt = null,
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
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping invalid remote row in reconcile $syncId: ${e.message}", e)
                    totalSkipped++
                    lastSkippedError = "Row $syncId skipped in reconcile due to invalid data"
                }
            }

            val now = System.currentTimeMillis()
            preferences.setLastFullReconcile(now)
            preferences.setLastPullTime(now)
            preferences.setLastSynced(now)
            if (lastSkippedError != null) {
                preferences.setLastError(lastSkippedError)
            } else {
                preferences.setLastError(null)
            }

            _syncStatus.value = _syncStatus.value.copy(
                state = SyncState.IDLE,
                lastSyncedAt = now,
                skippedCount = totalSkipped,
                errorMessage = lastSkippedError
            )

            val summary = ReconcileSummary(removedCount = removedCount, addedCount = addedCount)
            Log.i(TAG, "Full reconcile completed successfully: Removed $removedCount, added $addedCount")
            Result.success(summary)
        } catch (e: Exception) {
            handleSyncError(e)
            Result.failure(e)
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun checkAndRunAutoReconcile() {
        val lastReconcile = preferences.lastFullReconcileFlow.first() ?: 0L
        if (System.currentTimeMillis() - lastReconcile > SEVEN_DAYS_MS) {
            Log.i(TAG, "Last full reconcile was > 7 days ago (or never); running auto-reconcile")
            performFullReconcile()
        }
    }

    private fun handleSyncError(e: Throwable?) {
        Log.e(TAG, "Sync error: ${e?.message}", e)
        val plainMsg = when (e) {
            is SupabaseError.Network -> "Offline (network unreachable)"
            is SupabaseError.ConfigurationMissing -> "Supabase server URL or key missing"
            is SupabaseError.Auth -> "Session expired, please sign in"
            is SupabaseError.Server -> e.message ?: "Server error"
            else -> e?.message ?: "Sync error"
        }

        scope.launch {
            preferences.setLastError(plainMsg)
        }

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
                    errorMessage = plainMsg
                )
            }
            else -> {
                _syncStatus.value = _syncStatus.value.copy(
                    state = SyncState.ERROR,
                    errorMessage = plainMsg
                )
            }
        }
    }

    private fun calculateCursorWithOverlap(cursorIso: String?): String? {
        if (cursorIso.isNullOrBlank()) return null
        return try {
            val millis = parseIsoToMillis(cursorIso)
            val shifted = millis - CURSOR_OVERLAP_MS
            Instant.ofEpochMilli(shifted).toString()
        } catch (_: Exception) {
            cursorIso
        }
    }
}
