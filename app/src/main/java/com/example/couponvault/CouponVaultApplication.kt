package com.example.couponvault

import android.app.Application
import com.example.couponvault.data.AppDatabase
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.OverlayPreferences
import com.example.couponvault.util.AndroidClipboardHelper

class CouponVaultApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: CouponRepository by lazy {
        CouponRepository(database.couponDao())
    }

    val overlayPreferences: OverlayPreferences by lazy {
        OverlayPreferences(this)
    }

    val queueManager: CouponQueueManager by lazy {
        CouponQueueManager(
            couponDao = database.couponDao(),
            preferences = overlayPreferences,
            clipboardHelper = AndroidClipboardHelper(this)
        )
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
