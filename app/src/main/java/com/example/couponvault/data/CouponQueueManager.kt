package com.example.couponvault.data

import com.example.couponvault.util.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class QueueState(
    val currentCode: String? = null,
    val remainingCount: Int = 0,
    val isAllDone: Boolean = false,
    val currentCoupon: Coupon? = null,
    val autoMarkRedeemed: Boolean = true
)

class CouponQueueManager(
    private val couponDao: CouponDao,
    private val preferences: OverlayPreferences,
    private val clipboardHelper: ClipboardHelper,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val mutex = Mutex()

    val unscannedQueueFlow: Flow<List<Coupon>> = couponDao.getUnscannedQueueFlow()

    val queueState: StateFlow<QueueState> = combine(
        unscannedQueueFlow,
        preferences.currentCouponIdFlow,
        preferences.autoMarkRedeemedFlow
    ) { queue, currentId, autoMark ->
        val currentCoupon = currentId?.let { couponDao.getCouponById(it) }
        val remainingCount = queue.size
        val isAllDone = queue.isEmpty() && currentCoupon == null
        val codeToShow = currentCoupon?.code ?: queue.firstOrNull()?.code

        QueueState(
            currentCode = codeToShow,
            remainingCount = remainingCount,
            isAllDone = isAllDone,
            currentCoupon = currentCoupon,
            autoMarkRedeemed = autoMark
        )
    }.flowOn(Dispatchers.IO)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = QueueState()
        )

    /**
     * copyNext():
     * - If setting "Auto-mark previous as redeemed" is on (default on) and there is a current coupon,
     *   mark the current coupon REDEEMED (set redeemedAt).
     * - Then take the next queue item, put its code on the clipboard, and make it current.
     * - The very first call simply copies the first queue item.
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
                        redeemedAt = System.currentTimeMillis()
                    )
                    couponDao.update(redeemed)
                }
            }

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
     * markCurrentFailed(): sets current to FAILED, then copies the next item without marking failed one redeemed.
     */
    suspend fun markCurrentFailed(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val currentId = preferences.currentCouponIdFlow.first()
            val current = currentId?.let { couponDao.getCouponById(it) }

            if (current != null) {
                preferences.setPreviousCouponId(current.id)
                val failed = current.copy(
                    status = CouponStatus.FAILED,
                    redeemedAt = null
                )
                couponDao.update(failed)
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
     * undo(): steps back to the previous coupon, restoring its UNSCANNED status, and copies its code.
     */
    suspend fun undo(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prevId = preferences.previousCouponIdFlow.first()
            val prevCoupon = prevId?.let { couponDao.getCouponById(it) }

            if (prevCoupon != null) {
                val restored = prevCoupon.copy(
                    status = CouponStatus.UNSCANNED,
                    redeemedAt = null
                )
                couponDao.update(restored)
                preferences.setCurrentCouponId(restored.id)
                preferences.setPreviousCouponId(null)

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
}
