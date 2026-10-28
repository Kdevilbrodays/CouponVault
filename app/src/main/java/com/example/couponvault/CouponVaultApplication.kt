package com.example.couponvault

import android.app.Application
import com.example.couponvault.data.AppDatabase
import com.example.couponvault.data.BackgroundExtractionManager
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

    val extractionManager: BackgroundExtractionManager by lazy {
        BackgroundExtractionManager(
            context = this,
            couponDao = database.couponDao(),
            scope = applicationScope
        )
    }

    val supabasePreferences: com.example.couponvault.sync.SupabasePreferences by lazy {
        com.example.couponvault.sync.SupabasePreferences(this)
    }

    val supabaseClient: com.example.couponvault.sync.SupabaseClient by lazy {
        com.example.couponvault.sync.SupabaseClient(supabasePreferences)
    }

    val syncManager: com.example.couponvault.sync.SyncManager by lazy {
        com.example.couponvault.sync.SyncManager(
            repository = repository,
            client = supabaseClient,
            preferences = supabasePreferences,
            scope = applicationScope
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        // Initialize extraction manager on app start
        extractionManager
        // Initialize sync manager and schedule periodic sync
        syncManager
        try {
            com.example.couponvault.sync.SyncWorker.schedulePeriodicSync(this)
        } catch (t: Throwable) {
            Log.w("CouponVault", "SyncWorker scheduling skipped: ${t.message}")
        }
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
