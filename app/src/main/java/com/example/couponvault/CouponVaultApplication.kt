package com.example.couponvault

import android.app.Application
import com.example.couponvault.data.AppDatabase
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.OverlayPreferences
import com.example.couponvault.util.AndroidClipboardHelper

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CouponVaultApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(filesDir, CRASH_FILE_NAME)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile.writeText(sw.toString())
            } catch (e: Exception) {
                Log.e("CouponVault", "Failed to write last_crash.txt", e)
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        const val CRASH_FILE_NAME = "last_crash.txt"

        lateinit var instance: CouponVaultApplication
            private set
    }
}
