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

    @Query("SELECT * FROM coupons WHERE status = :status ORDER BY createdAt DESC")
    fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE id = :id LIMIT 1")
    suspend fun getCouponById(id: Long): Coupon?

    @Query("SELECT * FROM coupons WHERE id = :id LIMIT 1")
    fun getCouponByIdFlow(id: Long): Flow<Coupon?>

    @Query("SELECT * FROM coupons WHERE code = :code LIMIT 1")
    suspend fun getCouponByCode(code: String): Coupon?

    @Query("SELECT COUNT(*) FROM coupons WHERE status = 'UNSCANNED'")
    fun getUnscannedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM coupons WHERE imagePath = :imagePath")
    suspend fun countByImagePath(imagePath: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(coupon: Coupon): Long

    @Update
    suspend fun update(coupon: Coupon)

    @Delete
    suspend fun delete(coupon: Coupon)

    @Query("DELETE FROM coupons WHERE id = :id")
    suspend fun deleteById(id: Long)
}
