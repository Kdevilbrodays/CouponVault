package com.example.couponvault.data

import android.graphics.Rect
import com.example.couponvault.camera.BarcodeItem
import com.example.couponvault.util.CodeExtractor
import com.example.couponvault.util.OcrCodeSelector
import com.example.couponvault.util.OcrTokenCandidate
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
    val needsReviewCount: Flow<Int> = couponDao.getNeedsReviewCount()
    val needsReviewCoupons: Flow<List<Coupon>> = couponDao.getNeedsReviewCoupons()

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

    suspend fun updateCoupon(coupon: Coupon) = withContext(Dispatchers.IO) {
        couponDao.update(coupon)
    }

    suspend fun updateStatusBulk(ids: List<Long>, status: CouponStatus) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val redeemedTimestamp = if (status == CouponStatus.REDEEMED) System.currentTimeMillis() else null
        couponDao.updateStatusBulk(ids, status, redeemedTimestamp)
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

    suspend fun deleteCouponsBulk(ids: List<Long>): Boolean = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext false
        val targetCoupons = couponDao.getCouponsByIds(ids)
        couponDao.deleteByIds(ids)
        for (coupon in targetCoupons) {
            val remainingReferences = couponDao.countByImagePath(coupon.imagePath)
            if (remainingReferences <= 0) {
                val file = File(coupon.imagePath)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
        true
    }

    /**
     * Full Phase 2 decode pipeline:
     * - Decodes QR codes from image
     * - If no QR found, runs OCR fallback to find token ^[A-Z0-9]{10,20}$
     * - If both QR and OCR found and differ, keeps QR as code and flags NEEDS_REVIEW with ocrCode
     * - Deduplicates against database
     * - Cleans up duplicate image file if completely unreferenced
     */
    suspend fun processWithQrAndOcr(
        imageFile: File,
        barcodes: List<BarcodeItem>,
        ocrCandidates: List<OcrTokenCandidate>
    ): ProcessResult = withContext(Dispatchers.IO) {
        try {
            val validQrCodes = barcodes
                .mapNotNull { item -> CodeExtractor.extractCode(item.rawValue) }
                .distinct()

            val primaryBarcodeBox = barcodes.firstOrNull()?.boundingBox
            val bestOcrToken = OcrCodeSelector.selectBestOcrToken(ocrCandidates, primaryBarcodeBox)

            if (validQrCodes.isNotEmpty()) {
                // QR code(s) found
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
                        // Check for discrepancy between QR and OCR
                        val hasDiscrepancy = bestOcrToken != null && bestOcrToken != qrCode
                        val status = if (hasDiscrepancy) CouponStatus.NEEDS_REVIEW else CouponStatus.UNSCANNED

                        val coupon = Coupon(
                            code = qrCode,
                            imagePath = imageFile.absolutePath,
                            status = status,
                            source = CouponSource.QR,
                            createdAt = System.currentTimeMillis(),
                            ocrCode = bestOcrToken
                        )
                        val id = couponDao.insert(coupon)
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
            } else if (bestOcrToken != null) {
                // No QR found, but OCR found a candidate token
                val existing = couponDao.getCouponByCode(bestOcrToken)
                if (existing != null) {
                    val refCount = couponDao.countByImagePath(imageFile.absolutePath)
                    if (refCount <= 0 && imageFile.exists()) {
                        imageFile.delete()
                    }
                    return@withContext ProcessResult.AlreadySaved(
                        code = bestOcrToken,
                        existingCouponId = existing.id
                    )
                }

                val coupon = Coupon(
                    code = bestOcrToken,
                    imagePath = imageFile.absolutePath,
                    status = CouponStatus.NEEDS_REVIEW,
                    source = CouponSource.OCR,
                    createdAt = System.currentTimeMillis(),
                    ocrCode = bestOcrToken
                )
                val newId = couponDao.insert(coupon)
                return@withContext ProcessResult.Saved(bestOcrToken, newId)
            } else {
                // Neither QR nor OCR found a valid coupon code
                val coupon = Coupon(
                    code = null,
                    imagePath = imageFile.absolutePath,
                    status = CouponStatus.NEEDS_REVIEW,
                    source = CouponSource.QR,
                    createdAt = System.currentTimeMillis(),
                    ocrCode = null
                )
                val newId = couponDao.insert(coupon)
                return@withContext ProcessResult.NoCodeFound(newId)
            }
        } catch (e: Exception) {
            ProcessResult.Error(e.message ?: "Failed to decode photo")
        }
    }
}
