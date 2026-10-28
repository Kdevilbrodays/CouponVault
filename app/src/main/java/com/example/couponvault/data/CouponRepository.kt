package com.example.couponvault.data

import com.example.couponvault.camera.BarcodeItem
import com.example.couponvault.util.CodeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface ProcessResult {
    data class Saved(val code: String, val couponId: Long) : ProcessResult
    data class SavedMultiple(val codes: List<String>, val couponIds: List<Long>) : ProcessResult
    data class AlreadySaved(val code: String, val existingCouponId: Long) : ProcessResult
    data class Mixed(
        val savedCodes: List<String>,
        val duplicateCodes: List<String>,
        val highlightId: Long?
    ) : ProcessResult
    data class NoCodeFound(val couponId: Long) : ProcessResult
    data class Error(val message: String) : ProcessResult
}

class CouponRepository(private val couponDao: CouponDao) {

    private val _localDataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localDataChanged = _localDataChanged.asSharedFlow()

    val allCoupons: Flow<List<Coupon>> = couponDao.getAllCoupons()
    val unscannedCount: Flow<Int> = couponDao.getUnscannedCount()
    val redeemedCount: Flow<Int> = couponDao.getRedeemedCount()
    val needsReviewCount: Flow<Int> = couponDao.getNeedsReviewCount()
    val needsReviewCoupons: Flow<List<Coupon>> = couponDao.getNeedsReviewCoupons()
    val unsyncedCount: Flow<Int> = couponDao.getUnsyncedCount()

    fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>> =
        couponDao.getCouponsByStatus(status)

    suspend fun getAllCouponsList(): List<Coupon> = withContext(Dispatchers.IO) {
        couponDao.getAllCouponsList()
    }

    suspend fun getCouponById(id: Long): Coupon? = withContext(Dispatchers.IO) {
        couponDao.getCouponById(id)
    }

    fun getCouponByIdFlow(id: Long): Flow<Coupon?> =
        couponDao.getCouponByIdFlow(id)

    suspend fun getCouponByCode(code: String): Coupon? = withContext(Dispatchers.IO) {
        couponDao.getCouponByCode(code)
    }

    suspend fun insertCoupon(coupon: Coupon): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val toInsert = coupon.copy(
            syncId = if (coupon.syncId.isBlank()) UUID.randomUUID().toString() else coupon.syncId,
            modifiedAt = if (coupon.modifiedAt == 0L) now else coupon.modifiedAt,
            dirty = true
        )
        val id = couponDao.insert(toInsert)
        _localDataChanged.tryEmit(Unit)
        id
    }

    suspend fun getPendingCouponsList(): List<Coupon> = withContext(Dispatchers.IO) {
        couponDao.getPendingCouponsList()
    }

    suspend fun updateCoupon(coupon: Coupon) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val toUpdate = coupon.copy(
            modifiedAt = now,
            dirty = true
        )
        couponDao.update(toUpdate)
        _localDataChanged.tryEmit(Unit)
    }

    suspend fun updateStatusBulk(ids: List<Long>, status: CouponStatus) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val redeemedTimestamp = if (status == CouponStatus.REDEEMED) now else null
        couponDao.updateStatusBulk(ids, status, redeemedTimestamp, now)
        _localDataChanged.tryEmit(Unit)
    }

    /**
     * Deleting an unsynced coupon (serverUpdatedAt == null) hard-deletes it.
     * Deleting a previously synced coupon marks it as a tombstone (deletedAt = now, dirty = true)
     * so the delete is pushed to the server.
     */
    suspend fun deleteCoupon(coupon: Coupon): Boolean = withContext(Dispatchers.IO) {
        if (coupon.serverUpdatedAt == null) {
            couponDao.delete(coupon)
            val remainingReferences = couponDao.countByImagePath(coupon.imagePath)
            if (remainingReferences <= 0) {
                val file = File(coupon.imagePath)
                if (file.exists()) {
                    file.delete()
                }
            }
        } else {
            val now = System.currentTimeMillis()
            couponDao.update(
                coupon.copy(
                    deletedAt = now,
                    modifiedAt = now,
                    dirty = true
                )
            )
        }
        _localDataChanged.tryEmit(Unit)
        true
    }

    suspend fun deleteCouponsBulk(ids: List<Long>): Boolean = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext false
        val targetCoupons = couponDao.getCouponsByIds(ids)
        val now = System.currentTimeMillis()
        for (coupon in targetCoupons) {
            if (coupon.serverUpdatedAt == null) {
                couponDao.delete(coupon)
                val remainingReferences = couponDao.countByImagePath(coupon.imagePath)
                if (remainingReferences <= 0) {
                    val file = File(coupon.imagePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            } else {
                couponDao.update(
                    coupon.copy(
                        deletedAt = now,
                        modifiedAt = now,
                        dirty = true
                    )
                )
            }
        }
        _localDataChanged.tryEmit(Unit)
        true
    }

    suspend fun getRedeemedCouponsList(): List<Coupon> = withContext(Dispatchers.IO) {
        couponDao.getRedeemedCouponsList()
    }

    suspend fun deleteAllRedeemed(): Int = withContext(Dispatchers.IO) {
        val redeemed = couponDao.getRedeemedCouponsList()
        if (redeemed.isEmpty()) return@withContext 0
        deleteCouponsBulk(redeemed.map { it.id })
        redeemed.size
    }

    /**
     * Wipes all local coupon data (used on sign-out if user chooses to remove local coupons).
     */
    suspend fun hardDeleteAllCoupons(): Unit = withContext(Dispatchers.IO) {
        val all = couponDao.getAllCouponsList()
        couponDao.deleteAll()
        for (c in all) {
            val file = File(c.imagePath)
            if (file.exists()) {
                file.delete()
            }
        }
        _localDataChanged.tryEmit(Unit)
    }

    // --- Sync Support Operations ---

    suspend fun getDirtyCoupons(limit: Int = 50): List<Coupon> = withContext(Dispatchers.IO) {
        couponDao.getDirtyCoupons(limit)
    }

    suspend fun getCouponBySyncId(syncId: String): Coupon? = withContext(Dispatchers.IO) {
        couponDao.getCouponBySyncId(syncId)
    }

    suspend fun markClean(syncId: String, serverUpdatedAt: Long) = withContext(Dispatchers.IO) {
        couponDao.markClean(syncId, serverUpdatedAt)
    }

    suspend fun purgeBySyncId(syncId: String) = withContext(Dispatchers.IO) {
        val existing = couponDao.getCouponBySyncId(syncId)
        couponDao.purgeBySyncId(syncId)
        if (existing != null) {
            val refCount = couponDao.countByImagePath(existing.imagePath)
            if (refCount <= 0) {
                val f = File(existing.imagePath)
                if (f.exists()) f.delete()
            }
        }
    }

    suspend fun purgeBySyncIds(syncIds: List<String>) = withContext(Dispatchers.IO) {
        couponDao.purgeBySyncIds(syncIds)
    }

    suspend fun getUnsyncedCountSync(): Int = withContext(Dispatchers.IO) {
        couponDao.getUnsyncedCountSync()
    }

    /**
     * Saves a coupon from sync without marking it dirty.
     */
    suspend fun saveFromSync(coupon: Coupon) = withContext(Dispatchers.IO) {
        val existing = couponDao.getCouponBySyncId(coupon.syncId)
        if (existing == null) {
            couponDao.insert(coupon)
        } else {
            couponDao.update(coupon.copy(id = existing.id))
        }
    }

    /**
     * QR decoding is the sole automatic extraction path:
     * - Decodes QR code(s) from image.
     * - If no QR found (or invalid), saves photo with status NEEDS_REVIEW,
     *   empty code, and source = MANUAL.
     * - Deduplicates against database.
     * - Cleans up duplicate image file if completely unreferenced.
     */
    suspend fun processCouponPhoto(
        imageFile: File,
        barcodes: List<BarcodeItem>
    ): ProcessResult = withContext(Dispatchers.IO) {
        try {
            val validQrCodes = barcodes
                .mapNotNull { item -> CodeExtractor.extractCode(item.rawValue) }
                .distinct()

            if (validQrCodes.isNotEmpty()) {
                val savedCodes = mutableListOf<String>()
                val savedIds = mutableListOf<Long>()
                val duplicateCodes = mutableListOf<String>()
                var firstDuplicateId: Long? = null

                for (qrCode in validQrCodes) {
                    val existing = couponDao.getCouponByCode(qrCode)
                    if (existing != null) {
                        duplicateCodes.add(qrCode)
                        if (firstDuplicateId == null) firstDuplicateId = existing.id
                    } else {
                        val coupon = Coupon(
                            code = qrCode,
                            imagePath = imageFile.absolutePath,
                            status = CouponStatus.UNSCANNED,
                            source = CouponSource.QR,
                            createdAt = System.currentTimeMillis()
                        )
                        val id = insertCoupon(coupon)
                        savedCodes.add(qrCode)
                        savedIds.add(id)
                    }
                }

                if (savedCodes.isEmpty()) {
                    val refCount = couponDao.countByImagePath(imageFile.absolutePath)
                    if (refCount <= 0 && imageFile.exists()) {
                        imageFile.delete()
                    }
                    return@withContext ProcessResult.AlreadySaved(
                        code = duplicateCodes.firstOrNull() ?: "",
                        existingCouponId = firstDuplicateId ?: 0L
                    )
                }

                if (duplicateCodes.isEmpty()) {
                    return@withContext if (savedCodes.size == 1) {
                        ProcessResult.Saved(savedCodes.first(), savedIds.first())
                    } else {
                        ProcessResult.SavedMultiple(savedCodes, savedIds)
                    }
                }

                return@withContext ProcessResult.Mixed(
                    savedCodes = savedCodes,
                    duplicateCodes = duplicateCodes,
                    highlightId = firstDuplicateId ?: savedIds.firstOrNull()
                )
            } else {
                // No valid QR code found in photo
                val coupon = Coupon(
                    code = null,
                    imagePath = imageFile.absolutePath,
                    status = CouponStatus.NEEDS_REVIEW,
                    source = CouponSource.MANUAL,
                    createdAt = System.currentTimeMillis()
                )
                val newId = insertCoupon(coupon)
                return@withContext ProcessResult.NoCodeFound(newId)
            }
        } catch (e: Exception) {
            ProcessResult.Error(e.message ?: "Failed to decode photo")
        }
    }
}
