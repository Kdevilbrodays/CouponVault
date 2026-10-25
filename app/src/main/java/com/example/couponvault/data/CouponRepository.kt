package com.example.couponvault.data

import com.example.couponvault.util.CodeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

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

    val allCoupons: Flow<List<Coupon>> = couponDao.getAllCoupons()
    val unscannedCount: Flow<Int> = couponDao.getUnscannedCount()

    fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>> =
        couponDao.getCouponsByStatus(status)

    suspend fun getCouponById(id: Long): Coupon? = withContext(Dispatchers.IO) {
        couponDao.getCouponById(id)
    }

    fun getCouponByIdFlow(id: Long): Flow<Coupon?> =
        couponDao.getCouponByIdFlow(id)

    suspend fun getCouponByCode(code: String): Coupon? = withContext(Dispatchers.IO) {
        couponDao.getCouponByCode(code)
    }

    suspend fun updateCoupon(coupon: Coupon) = withContext(Dispatchers.IO) {
        couponDao.update(coupon)
    }

    suspend fun deleteCoupon(coupon: Coupon): Boolean = withContext(Dispatchers.IO) {
        couponDao.delete(coupon)
        val remainingReferences = couponDao.countByImagePath(coupon.imagePath)
        if (remainingReferences <= 0) {
            val file = File(coupon.imagePath)
            if (file.exists()) {
                file.delete()
            }
        }
        true
    }

    /**
     * Processes a newly captured photo:
     * - Parses all raw QR barcode texts with CodeExtractor
     * - If no valid code found, creates a coupon with code=null, status=NEEDS_REVIEW
     * - If codes found:
     *     - Deduplicates against existing records
     *     - If all codes are duplicates, deletes the newly captured unreferenced file and returns AlreadySaved
     *     - Otherwise inserts new coupons for each distinct new code and returns Saved / Mixed
     */
    suspend fun processCapturedPhoto(
        imageFile: File,
        rawQrTexts: List<String>
    ): ProcessResult = withContext(Dispatchers.IO) {
        try {
            val validCodes = rawQrTexts
                .mapNotNull { CodeExtractor.extractCode(it) }
                .distinct()

            if (validCodes.isEmpty()) {
                val coupon = Coupon(
                    code = null,
                    imagePath = imageFile.absolutePath,
                    status = CouponStatus.NEEDS_REVIEW,
                    source = CouponSource.QR,
                    createdAt = System.currentTimeMillis()
                )
                val newId = couponDao.insert(coupon)
                return@withContext ProcessResult.NoCodeFound(newId)
            }

            val savedCodes = mutableListOf<String>()
            val savedIds = mutableListOf<Long>()
            val duplicateCodes = mutableListOf<String>()
            var firstDuplicateId: Long? = null

            for (code in validCodes) {
                val existing = couponDao.getCouponByCode(code)
                if (existing != null) {
                    duplicateCodes.add(code)
                    if (firstDuplicateId == null) {
                        firstDuplicateId = existing.id
                    }
                } else {
                    val coupon = Coupon(
                        code = code,
                        imagePath = imageFile.absolutePath,
                        status = CouponStatus.UNSCANNED,
                        source = CouponSource.QR,
                        createdAt = System.currentTimeMillis()
                    )
                    val id = couponDao.insert(coupon)
                    savedCodes.add(code)
                    savedIds.add(id)
                }
            }

            if (savedCodes.isEmpty()) {
                // All found codes were duplicates!
                // Per requirement: If the new photo is then unreferenced, delete only that new duplicate file.
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

            ProcessResult.Mixed(
                savedCodes = savedCodes,
                duplicateCodes = duplicateCodes,
                highlightId = firstDuplicateId ?: savedIds.firstOrNull()
            )
        } catch (e: Exception) {
            ProcessResult.Error(e.message ?: "Failed to process photo")
        }
    }
}
