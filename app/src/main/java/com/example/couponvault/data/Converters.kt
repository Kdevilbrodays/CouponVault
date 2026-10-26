package com.example.couponvault.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(status: CouponStatus?): String? = status?.name

    @TypeConverter
    fun toStatus(value: String?): CouponStatus? =
        value?.let { runCatching { CouponStatus.valueOf(it) }.getOrDefault(CouponStatus.NEEDS_REVIEW) }

    @TypeConverter
    fun fromSource(source: CouponSource?): String? = source?.name

    @TypeConverter
    fun toSource(value: String?): CouponSource? =
        value?.let { runCatching { CouponSource.valueOf(it) }.getOrDefault(CouponSource.MANUAL) }
}
