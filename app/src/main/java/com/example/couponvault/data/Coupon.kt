package com.example.couponvault.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "coupons",
    indices = [
        Index(value = ["code"], unique = true)
    ]
)
data class Coupon(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val code: String?,
    val imagePath: String,
    val status: CouponStatus,
    val source: CouponSource,
    val createdAt: Long = System.currentTimeMillis(),
    val redeemedAt: Long? = null,
    val ocrCode: String? = null
)
