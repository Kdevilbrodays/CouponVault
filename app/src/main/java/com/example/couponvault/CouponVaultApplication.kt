package com.example.couponvault

import android.app.Application
import com.example.couponvault.data.AppDatabase
import com.example.couponvault.data.CouponRepository

class CouponVaultApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: CouponRepository by lazy {
        CouponRepository(database.couponDao())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CouponVaultApplication
            private set
    }
}
