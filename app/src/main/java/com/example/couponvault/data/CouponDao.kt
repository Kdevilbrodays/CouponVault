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
    @Query("SELECT * FROM coupons ORDER BY createdAt DESC")
    fun getAllCoupons(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons ORDER BY createdAt DESC")
    suspend fun getAllCouponsList(): List<Coupon>

    @Query("SELECT * FROM coupons WHERE status = :status ORDER BY createdAt DESC")
    fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE status = 'NEEDS_REVIEW' AND extractionState = 'DONE' ORDER BY createdAt DESC")
    fun getNeedsReviewCoupons(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE status = 'UNSCANNED' AND code IS NOT NULL AND code != '' ORDER BY createdAt ASC")
    fun getUnscannedQueueFlow(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE status = 'UNSCANNED' AND code IS NOT NULL AND code != '' ORDER BY createdAt ASC")
    suspend fun getUnscannedQueueList(): List<Coupon>

    @Query("SELECT * FROM coupons WHERE id = :id LIMIT 1")
    suspend fun getCouponById(id: Long): Coupon?

    @Query("SELECT * FROM coupons WHERE id IN (:ids)")
    suspend fun getCouponsByIds(ids: List<Long>): List<Coupon>

    @Query("SELECT * FROM coupons WHERE id = :id LIMIT 1")
    fun getCouponByIdFlow(id: Long): Flow<Coupon?>

    @Query("SELECT * FROM coupons WHERE code = :code LIMIT 1")
    suspend fun getCouponByCode(code: String): Coupon?

    @Query("SELECT COUNT(*) FROM coupons WHERE status = 'UNSCANNED'")
    fun getUnscannedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM coupons WHERE status = 'NEEDS_REVIEW' AND extractionState = 'DONE'")
    fun getNeedsReviewCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM coupons WHERE imagePath = :imagePath")
    suspend fun countByImagePath(imagePath: String): Int

    @Query("SELECT * FROM coupons WHERE extractionState = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingCouponsList(): List<Coupon>

    @Query("SELECT * FROM coupons WHERE extractionState = 'PENDING' ORDER BY createdAt ASC")
    fun getPendingCouponsFlow(): Flow<List<Coupon>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(coupon: Coupon): Long

    @Update
    suspend fun update(coupon: Coupon)

    @Query("UPDATE coupons SET status = :status, redeemedAt = :redeemedAt WHERE id IN (:ids)")
    suspend fun updateStatusBulk(ids: List<Long>, status: CouponStatus, redeemedAt: Long?)

    @Delete
    suspend fun delete(coupon: Coupon)

    @Query("DELETE FROM coupons WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM coupons WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
