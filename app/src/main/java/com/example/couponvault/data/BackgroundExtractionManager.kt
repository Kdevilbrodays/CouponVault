package com.example.couponvault.data

import android.content.Context
import android.util.Log
import com.example.couponvault.camera.BarcodeItem
import com.example.couponvault.camera.BarcodeScannerHelper
import com.example.couponvault.util.CodeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

sealed interface ExtractionEvent {
    data class CodeFound(val couponId: Long, val code: String) : ExtractionEvent
    data class DuplicateSkipped(val code: String, val existingCouponId: Long) : ExtractionEvent
    data class NoCodeFound(val couponId: Long) : ExtractionEvent
}

/**
 * App-scoped extraction worker pipeline:
 * - Channel-driven coroutine on Dispatchers.Default with parallelism 2.
 * - Tied to the application lifecycle, independent of screen or ViewModel.
 * - Re-queues any rows still PENDING on start.
 * - Automatically handles deduplication and unreferenced file cleanup.
 */
class BackgroundExtractionManager(
    private val context: Context,
    private val couponDao: CouponDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val scanner: suspend (Context, File) -> List<BarcodeItem> = { ctx, file ->
        BarcodeScannerHelper.scanBarcodesWithBounds(ctx, file)
    }
) {
    private val channel = Channel<Long>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<ExtractionEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<ExtractionEvent> = _events.asSharedFlow()

    private val queuedIds = Collections.synchronizedSet(mutableSetOf<Long>())

    init {
        // Parallelism 2: launch two concurrent worker coroutines on Dispatchers.Default
        repeat(2) {
            scope.launch(Dispatchers.Default) {
                for (couponId in channel) {
                    try {
                        processCoupon(couponId)
                    } catch (e: Exception) {
                        Log.e("BackgroundExtraction", "Failed to extract coupon #$couponId", e)
                    } finally {
                        queuedIds.remove(couponId)
                    }
                }
            }
        }

        // On app start, re-queue any rows still PENDING
        scope.launch(Dispatchers.IO) {
            try {
                val pending = couponDao.getPendingCouponsList()
                for (coupon in pending) {
                    enqueue(coupon.id)
                }
            } catch (e: Exception) {
                Log.e("BackgroundExtraction", "Failed to re-queue pending coupons on start", e)
            }
        }
    }

    fun enqueue(couponId: Long) {
        if (queuedIds.add(couponId)) {
            channel.trySend(couponId)
        }
    }

    suspend fun processCoupon(couponId: Long) {
        val coupon = couponDao.getCouponById(couponId) ?: return
        if (coupon.extractionState == ExtractionState.DONE) return

        val file = File(coupon.imagePath)
        if (!file.exists()) {
            // Original file is missing, mark as DONE
            couponDao.update(coupon.copy(extractionState = ExtractionState.DONE))
            try {
                com.example.couponvault.CouponVaultApplication.instance.syncManager.pushNowImmediately()
            } catch (_: Throwable) {}
            return
        }

        val barcodes = try {
            scanner(context, file)
        } catch (e: Exception) {
            Log.e("BackgroundExtraction", "Scanner exception on photo #$couponId", e)
            emptyList()
        }

        val validCodes = barcodes
            .mapNotNull { CodeExtractor.extractCode(it.rawValue) }
            .distinct()

        if (validCodes.isNotEmpty()) {
            val code = validCodes.first()
            val existing = couponDao.getCouponByCode(code)
            if (existing != null && existing.id != coupon.id) {
                // Duplicate code -> keep existing entry, remove new row,
                // delete new file only if nothing else references it,
                // show small non-blocking "Duplicate skipped" chip.
                couponDao.delete(coupon)
                val remaining = couponDao.countByImagePath(coupon.imagePath)
                if (remaining <= 0 && file.exists()) {
                    file.delete()
                }
                _events.emit(ExtractionEvent.DuplicateSkipped(code, existing.id))
            } else {
                // Code found -> status UNSCANNED, source QR, extractionState DONE
                val updated = coupon.copy(
                    code = code,
                    status = CouponStatus.UNSCANNED,
                    source = CouponSource.QR,
                    extractionState = ExtractionState.DONE
                )
                couponDao.update(updated)
                _events.emit(ExtractionEvent.CodeFound(coupon.id, code))
                try {
                    com.example.couponvault.CouponVaultApplication.instance.syncManager.pushNowImmediately()
                } catch (_: Throwable) {}
            }
        } else {
            // None found -> stays NEEDS_REVIEW, extractionState DONE
            val updated = coupon.copy(
                code = null,
                status = CouponStatus.NEEDS_REVIEW,
                source = CouponSource.MANUAL,
                extractionState = ExtractionState.DONE
            )
            couponDao.update(updated)
            _events.emit(ExtractionEvent.NoCodeFound(coupon.id))
            try {
                com.example.couponvault.CouponVaultApplication.instance.syncManager.pushNowImmediately()
            } catch (_: Throwable) {}
        }
    }
}
