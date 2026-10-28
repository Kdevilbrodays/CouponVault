package com.example.couponvault.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "coupons",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["syncId"], unique = true)
    ]
)
data class Coupon(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val code: String? = null,
    val imagePath: String = "",
    val status: CouponStatus = CouponStatus.UNSCANNED,
    val source: CouponSource = CouponSource.MANUAL,
    val createdAt: Long = System.currentTimeMillis(),
    val redeemedAt: Long? = null,
    val extractionState: ExtractionState = ExtractionState.DONE,
    val modifiedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val dirty: Boolean = true,
    val serverUpdatedAt: Long? = null,
    val remoteImagePath: String? = null,
    val imageSha256: String? = null,
    val imageVariant: String? = null,
    val claimedBy: String? = null,
    val claimedAt: Long? = null
)
