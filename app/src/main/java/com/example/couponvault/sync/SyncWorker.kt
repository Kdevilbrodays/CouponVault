package com.example.couponvault.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.couponvault.CouponVaultApplication
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME = "coupon_vault_sync_work"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncWorkRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? CouponVaultApplication ?: return Result.failure()
        return try {
            val success = app.syncManager.performSync()
            if (success) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
