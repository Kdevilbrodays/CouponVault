package com.example.couponvault.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CouponDao {
    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun getAllCoupons(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    suspend fun getAllCouponsList(): List<Coupon>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND status = :status ORDER BY createdAt DESC")
    fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND status = 'NEEDS_REVIEW' AND extractionState = 'DONE' ORDER BY createdAt DESC")
    fun getNeedsReviewCoupons(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND status = 'UNSCANNED' AND code IS NOT NULL AND code != '' ORDER BY createdAt ASC")
    fun getUnscannedQueueFlow(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND status = 'UNSCANNED' AND code IS NOT NULL AND code != '' ORDER BY createdAt ASC")
    suspend fun getUnscannedQueueList(): List<Coupon>

    @Query("SELECT * FROM coupons WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getCouponById(id: Long): Coupon?

    @Query("SELECT * FROM coupons WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun getCouponsByIds(ids: List<Long>): List<Coupon>

    @Query("SELECT * FROM coupons WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun getCouponByIdFlow(id: Long): Flow<Coupon?>

    @Query("SELECT * FROM coupons WHERE code = :code AND deletedAt IS NULL LIMIT 1")
    suspend fun getCouponByCode(code: String): Coupon?

    @Query("SELECT COUNT(*) FROM coupons WHERE deletedAt IS NULL AND status = 'UNSCANNED'")
    fun getUnscannedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM coupons WHERE deletedAt IS NULL AND status = 'NEEDS_REVIEW' AND extractionState = 'DONE'")
    fun getNeedsReviewCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM coupons WHERE deletedAt IS NULL AND status = 'REDEEMED'")
    fun getRedeemedCount(): Flow<Int>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND status = 'REDEEMED' ORDER BY createdAt DESC")
    suspend fun getRedeemedCouponsList(): List<Coupon>

    @Query("SELECT COUNT(*) FROM coupons WHERE imagePath = :imagePath AND deletedAt IS NULL")
    suspend fun countByImagePath(imagePath: String): Int

    @Query("DELETE FROM coupons")
    suspend fun deleteAll()

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND extractionState = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingCouponsList(): List<Coupon>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND extractionState = 'PENDING' ORDER BY createdAt ASC")
    fun getPendingCouponsFlow(): Flow<List<Coupon>>

    // --- Sync Specific Queries ---

    @Query("SELECT * FROM coupons WHERE dirty = 1 AND extractionState = 'DONE' ORDER BY modifiedAt ASC LIMIT :limit")
    suspend fun getDirtyCoupons(limit: Int): List<Coupon>

    @Query("SELECT * FROM coupons")
    suspend fun getAllCouponsIncludingTombstones(): List<Coupon>

    @Query("DELETE FROM coupons WHERE serverUpdatedAt IS NULL")
    suspend fun deleteUnsyncedCoupons()

    @Query("UPDATE coupons SET deletedAt = :now, modifiedAt = :now, dirty = 1 WHERE serverUpdatedAt IS NOT NULL AND deletedAt IS NULL")
    suspend fun tombstoneSyncedCoupons(now: Long)

    @Query("SELECT * FROM coupons WHERE syncId = :syncId LIMIT 1")
    suspend fun getCouponBySyncId(syncId: String): Coupon?

    @Query("UPDATE coupons SET dirty = 0, serverUpdatedAt = :serverUpdatedAt WHERE syncId = :syncId")
    suspend fun markClean(syncId: String, serverUpdatedAt: Long)

    @Query("DELETE FROM coupons WHERE syncId = :syncId")
    suspend fun purgeBySyncId(syncId: String)

    @Query("DELETE FROM coupons WHERE syncId IN (:syncIds)")
    suspend fun purgeBySyncIds(syncIds: List<String>)

    @Query("SELECT COUNT(*) FROM coupons WHERE dirty = 1 AND extractionState = 'DONE'")
    fun getUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM coupons WHERE dirty = 1 AND extractionState = 'DONE'")
    suspend fun getUnsyncedCountSync(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(coupon: Coupon): Long

    @Update
    suspend fun update(coupon: Coupon)

    @Query("UPDATE coupons SET status = :status, redeemedAt = :redeemedAt, modifiedAt = :modifiedAt, dirty = 1 WHERE id IN (:ids)")
    suspend fun updateStatusBulk(ids: List<Long>, status: CouponStatus, redeemedAt: Long?, modifiedAt: Long)

    @Delete
    suspend fun delete(coupon: Coupon)

    @Query("DELETE FROM coupons WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM coupons WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // --- Photo Sync Queries ---

    @Query("SELECT * FROM coupons WHERE serverUpdatedAt IS NOT NULL AND deletedAt IS NULL AND (remoteImagePath IS NULL OR remoteImagePath = '') AND imagePath IS NOT NULL AND imagePath != ''")
    suspend fun getCouponsNeedingPhotoUpload(): List<Coupon>

    @Query("SELECT * FROM coupons WHERE deletedAt IS NULL AND remoteImagePath IS NOT NULL AND remoteImagePath != '' AND (imagePath = '' OR imagePath IS NULL)")
    suspend fun getCouponsNeedingPhotoDownload(): List<Coupon>

    @Query("UPDATE coupons SET remoteImagePath = :remoteImagePath, imageSha256 = :imageSha256, imageVariant = :imageVariant, dirty = 1, modifiedAt = :modifiedAt WHERE syncId = :syncId")
    suspend fun updatePhotoUploadSuccess(syncId: String, remoteImagePath: String, imageSha256: String, imageVariant: String, modifiedAt: Long)

    @Query("UPDATE coupons SET imagePath = :localImagePath WHERE syncId = :syncId")
    suspend fun updateLocalImagePath(syncId: String, localImagePath: String)
}
