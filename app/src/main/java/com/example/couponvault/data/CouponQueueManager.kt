package com.example.couponvault.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.couponvault.sync.SyncManager
import com.example.couponvault.sync.SupabaseClient
import com.example.couponvault.sync.SupabasePreferences
import com.example.couponvault.util.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class QueueState(
    val currentCode: String? = null,
    val remainingCount: Int = 0,
    val isAllDone: Boolean = false,
    val currentCoupon: Coupon? = null,
    val autoMarkRedeemed: Boolean = true,
    val isLastCopyFallback: Boolean = false
)

class CouponQueueManager(
    private val couponDao: CouponDao,
    private val preferences: OverlayPreferences,
    private val clipboardHelper: ClipboardHelper,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val supabaseClient: SupabaseClient? = null,
    private val supabasePreferences: SupabasePreferences? = null,
    private val repository: CouponRepository? = null,
    private val context: Context? = null,
    private val onlineChecker: (() -> Boolean)? = null,
    private val onImmediatePushNeeded: (suspend () -> Unit)? = null
) {
    private val mutex = Mutex()

    private val _isLastCopyFallback = MutableStateFlow(false)
    val isLastCopyFallback: StateFlow<Boolean> = _isLastCopyFallback.asStateFlow()

    private val _remoteAllDone = MutableStateFlow(false)

    val unscannedQueueFlow: Flow<List<Coupon>> = couponDao.getUnscannedQueueFlow()

    val queueState: StateFlow<QueueState> = combine(
        unscannedQueueFlow,
        preferences.currentCouponIdFlow,
        preferences.autoMarkRedeemedFlow,
        _isLastCopyFallback,
        _remoteAllDone
    ) { queue, currentId, autoMark, isFallback, remoteAllDone ->
        val currentCoupon = currentId?.let { couponDao.getCouponById(it) }
        val remainingCount = queue.size
        val isAllDone = (remoteAllDone && currentCoupon == null) || (queue.isEmpty() && currentCoupon == null)
        val codeToShow = currentCoupon?.code ?: queue.firstOrNull()?.code

        QueueState(
            currentCode = codeToShow,
            remainingCount = remainingCount,
            isAllDone = isAllDone,
            currentCoupon = currentCoupon,
            autoMarkRedeemed = autoMark,
            isLastCopyFallback = isFallback
        )
    }.flowOn(Dispatchers.IO)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = QueueState()
        )

    private fun isOnline(): Boolean {
        if (onlineChecker != null) return onlineChecker.invoke()
        val ctx = context ?: return true
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun parseTimestamp(value: Any?): Long {
        if (value == null || value == JSONObject.NULL) return System.currentTimeMillis()
        if (value is Number) return value.toLong()
        val str = value.toString().trim().removeSurrounding("\"")
        return try {
            SyncManager.parseIsoToMillis(str)
        } catch (_: Exception) {
            str.toLongOrNull() ?: System.currentTimeMillis()
        }
    }

    private fun parseRemoteCoupon(item: JSONObject): Coupon {
        val syncId = item.getString("id")
        val code = if (item.isNull("code")) null else item.getString("code")
        val status = try {
            CouponStatus.valueOf(item.optString("status", "UNSCANNED"))
        } catch (_: Exception) {
            CouponStatus.UNSCANNED
        }
        val source = try {
            CouponSource.valueOf(item.optString("source", "QR"))
        } catch (_: Exception) {
            CouponSource.QR
        }
        val extractionState = try {
            ExtractionState.valueOf(item.optString("extraction_state", "DONE"))
        } catch (_: Exception) {
            ExtractionState.DONE
        }
        val createdAt = SyncManager.tsMillis(item, "created_at")
        val modifiedAt = SyncManager.tsMillis(item, "modified_at")
        val redeemedAt = if (item.isNull("redeemed_at")) null else SyncManager.tsMillis(item, "redeemed_at")
        val deletedAt = if (item.isNull("deleted_at")) null else SyncManager.tsMillis(item, "deleted_at")
        val claimedBy = if (item.isNull("claimed_by")) null else item.getString("claimed_by")
        val claimedAt = if (item.isNull("claimed_at")) null else SyncManager.tsMillis(item, "claimed_at")
        val remoteImagePath = if (item.isNull("image_path")) null else item.getString("image_path")
        val imageSha256 = if (item.isNull("image_sha256")) null else item.getString("image_sha256")
        val imageVariant = if (item.isNull("image_variant")) null else item.getString("image_variant")
        val serverUpdatedAt = if (item.isNull("updated_at")) null else SyncManager.tsMillis(item, "updated_at")

        return Coupon(
            id = 0L,
            syncId = syncId,
            code = code,
            imagePath = "",
            status = status,
            source = source,
            createdAt = createdAt,
            redeemedAt = redeemedAt,
            extractionState = extractionState,
            modifiedAt = modifiedAt,
            deletedAt = deletedAt,
            dirty = false,
            serverUpdatedAt = serverUpdatedAt,
            remoteImagePath = remoteImagePath,
            imageSha256 = imageSha256,
            imageVariant = imageVariant,
            claimedBy = claimedBy,
            claimedAt = claimedAt
        )
    }

    private suspend fun fallbackLocalCopyNext(autoMark: Boolean, current: Coupon?): String? {
        val queue = couponDao.getUnscannedQueueList()
        val nextCoupon = if (autoMark) {
            queue.firstOrNull()
        } else {
            if (current != null) {
                queue.firstOrNull { it.id != current.id && it.createdAt >= current.createdAt }
                    ?: queue.firstOrNull { it.id != current.id }
            } else {
                queue.firstOrNull()
            }
        }

        return if (nextCoupon != null) {
            val code = nextCoupon.code ?: ""
            clipboardHelper.copyText(code, isSensitive = true)
            preferences.setCurrentCouponId(nextCoupon.id)
            code
        } else {
            preferences.setCurrentCouponId(null)
            null
        }
    }

    /**
     * copyNext():
     * - If setting "Auto-mark previous as redeemed" is on (default on) and there is a current coupon,
     *   mark the current coupon REDEEMED (set redeemedAt, dirty = true) and trigger debounced push.
     * - If signed in and online: call claim_next_coupon(deviceId) with a 2-second timeout.
     *   - If it returns a row: ensure coupon exists locally (insert if not), make it current coupon, copy its code.
     *   - If claim returns empty set: show "All done".
     *   - If claim times out or fails: silently fall back to existing local queue logic without claim, setting fallback indicator.
     * - If signed out or offline: keep behavior unchanged (fallback indicator active only when signed in but offline).
     */
    suspend fun copyNext(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val autoMark = preferences.autoMarkRedeemedFlow.first()
            val currentId = preferences.currentCouponIdFlow.first()
            val current = currentId?.let { couponDao.getCouponById(it) }

            if (current != null) {
                preferences.setPreviousCouponId(current.id)
                if (autoMark) {
                    val redeemed = current.copy(
                        status = CouponStatus.REDEEMED,
                        redeemedAt = System.currentTimeMillis(),
                        dirty = true,
                        modifiedAt = System.currentTimeMillis()
                    )
                    if (repository != null) {
                        repository.updateCoupon(redeemed)
                    } else {
                        couponDao.update(redeemed)
                    }
                    onImmediatePushNeeded?.invoke()
                }
            }

            val token = supabasePreferences?.getAccessToken()
            val isSignedIn = !token.isNullOrBlank() && supabaseClient != null
            val online = isSignedIn && isOnline()

            if (isSignedIn && online) {
                val deviceId = preferences.getOrCreateDeviceId()
                var claimedRow: JSONObject? = null
                var claimSuccess = false
                var timedOutOrFailed = false

                try {
                    val result = withTimeoutOrNull(2000L) {
                        supabaseClient?.claimNextCoupon(deviceId)
                    }
                    if (result == null) {
                        timedOutOrFailed = true
                    } else if (result.isSuccess) {
                        claimSuccess = true
                        claimedRow = result.getOrNull()
                    } else {
                        timedOutOrFailed = true
                    }
                } catch (_: Throwable) {
                    timedOutOrFailed = true
                }

                if (claimSuccess) {
                    _remoteAllDone.value = false
                    if (claimedRow != null) {
                        _isLastCopyFallback.value = false
                        val syncId = claimedRow.getString("id")
                        var localCoupon = couponDao.getCouponBySyncId(syncId)
                        if (localCoupon == null) {
                            val parsed = parseRemoteCoupon(claimedRow)
                            val insertedId = couponDao.insert(parsed)
                            localCoupon = parsed.copy(id = insertedId)
                        } else {
                            val updated = localCoupon.copy(
                                claimedBy = if (claimedRow.isNull("claimed_by")) deviceId else claimedRow.getString("claimed_by"),
                                claimedAt = if (claimedRow.isNull("claimed_at")) System.currentTimeMillis() else SyncManager.tsMillis(claimedRow, "claimed_at"),
                                status = CouponStatus.UNSCANNED
                            )
                            couponDao.update(updated)
                            localCoupon = updated
                        }

                        preferences.setCurrentCouponId(localCoupon.id)
                        val code = localCoupon.code ?: ""
                        if (code.isNotBlank()) {
                            clipboardHelper.copyText(code, isSensitive = true)
                        }
                        return@withContext code
                    } else {
                        // Empty set: show "All done"
                        _isLastCopyFallback.value = false
                        _remoteAllDone.value = true
                        preferences.setCurrentCouponId(null)
                        return@withContext null
                    }
                }

                // If claim timed out or failed: silently fall back to existing local queue logic without claim
                if (timedOutOrFailed) {
                    _isLastCopyFallback.value = true
                    return@withContext fallbackLocalCopyNext(autoMark, current)
                }
            } else if (isSignedIn && !online) {
                // Signed in but offline: offline fallback
                _isLastCopyFallback.value = true
                return@withContext fallbackLocalCopyNext(autoMark, current)
            } else {
                // Signed out: keep behavior and UI unchanged
                _isLastCopyFallback.value = false
                return@withContext fallbackLocalCopyNext(autoMark, current)
            }

            fallbackLocalCopyNext(autoMark, current)
        }
    }

    /**
     * repeat(): re-copies the current code to clipboard.
     */
    suspend fun repeat(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val currentId = preferences.currentCouponIdFlow.first()
            var current = currentId?.let { couponDao.getCouponById(it) }
            if (current == null) {
                current = couponDao.getUnscannedQueueList().firstOrNull()
                current?.let { preferences.setCurrentCouponId(it.id) }
            }

            if (current != null && !current.code.isNullOrBlank()) {
                clipboardHelper.copyText(current.code, isSensitive = true)
                current.code
            } else {
                null
            }
        }
    }

    /**
     * markCurrentFailed(): sets current to FAILED, releases remote claim fire-and-forget,
     * then copies the next item without marking failed one redeemed.
     */
    suspend fun markCurrentFailed(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val currentId = preferences.currentCouponIdFlow.first()
            val current = currentId?.let { couponDao.getCouponById(it) }

            if (current != null) {
                preferences.setPreviousCouponId(current.id)
                val failed = current.copy(
                    status = CouponStatus.FAILED,
                    redeemedAt = null,
                    dirty = true,
                    modifiedAt = System.currentTimeMillis()
                )
                if (repository != null) {
                    repository.updateCoupon(failed)
                } else {
                    couponDao.update(failed)
                }

                // Release remote claim fire-and-forget
                val syncId = current.syncId
                if (syncId.isNotBlank() && supabaseClient != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            supabaseClient.releaseCoupon(syncId)
                        } catch (e: Exception) {
                            Log.w("CouponQueueManager", "releaseCoupon fire-and-forget failed: ${e.message}")
                        }
                    }
                }
            }

            val queue = couponDao.getUnscannedQueueList()
            val nextCoupon = queue.firstOrNull()
            if (nextCoupon != null) {
                val code = nextCoupon.code ?: ""
                clipboardHelper.copyText(code, isSensitive = true)
                preferences.setCurrentCouponId(nextCoupon.id)
                code
            } else {
                preferences.setCurrentCouponId(null)
                null
            }
        }
    }

    /**
     * undo(): steps back to the previous coupon, restoring its UNSCANNED status,
     * releases remote claim fire-and-forget, and copies its code.
     */
    suspend fun undo(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prevId = preferences.previousCouponIdFlow.first()
            val prevCoupon = prevId?.let { couponDao.getCouponById(it) }

            if (prevCoupon != null) {
                val restored = prevCoupon.copy(
                    status = CouponStatus.UNSCANNED,
                    redeemedAt = null,
                    dirty = true,
                    modifiedAt = System.currentTimeMillis()
                )
                if (repository != null) {
                    repository.updateCoupon(restored)
                } else {
                    couponDao.update(restored)
                }
                preferences.setCurrentCouponId(restored.id)
                preferences.setPreviousCouponId(null)

                // Release remote claim fire-and-forget
                val syncId = prevCoupon.syncId
                if (syncId.isNotBlank() && supabaseClient != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            supabaseClient.releaseCoupon(syncId)
                        } catch (e: Exception) {
                            Log.w("CouponQueueManager", "releaseCoupon fire-and-forget failed: ${e.message}")
                        }
                    }
                }

                if (!restored.code.isNullOrBlank()) {
                    clipboardHelper.copyText(restored.code, isSensitive = true)
                    restored.code
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    suspend fun setAutoMarkRedeemed(enabled: Boolean) {
        preferences.setAutoMarkRedeemed(enabled)
    }

    /**
     * Called when a coupon is deleted or marked redeemed remotely by sync.
     * If the current pointer points to this coupon, advances pointer to the next coupon in queue
     * while retaining any text already copied to clipboard.
     */
    suspend fun onCouponRemovedOrRedeemed(couponId: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val currentId = preferences.currentCouponIdFlow.first()
            if (currentId == couponId) {
                val queue = couponDao.getUnscannedQueueList()
                val nextCoupon = queue.firstOrNull { it.id != couponId }
                preferences.setCurrentCouponId(nextCoupon?.id)
            }
        }
    }
}
