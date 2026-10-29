package com.example.couponvault.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.couponvault.CouponVaultApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

class PhotoUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PhotoUploadWorker"
        const val UNIQUE_WORK_NAME = "coupon_vault_photo_upload_work"

        private val _uploadingSyncIds = MutableStateFlow<Set<String>>(emptySet())
        val uploadingSyncIds: StateFlow<Set<String>> = _uploadingSyncIds.asStateFlow()

        fun markUploading(syncId: String, isUploading: Boolean) {
            if (isUploading) {
                _uploadingSyncIds.value = _uploadingSyncIds.value + syncId
            } else {
                _uploadingSyncIds.value = _uploadingSyncIds.value - syncId
            }
        }

        /**
         * Enqueues or replaces the unique photo upload job with specified network & battery constraints.
         */
        fun enqueue(context: Context, wifiOnly: Boolean = true) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val uploadWorkRequest = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                uploadWorkRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? CouponVaultApplication ?: return Result.failure()
        val prefs = app.supabasePreferences
        val client = app.supabaseClient
        val repository = app.repository
        val syncManager = app.syncManager

        // 1. Check if signed in and photo sync enabled
        val userId = prefs.userIdFlow.first()
        val accessToken = prefs.getAccessToken()
        if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            Log.d(TAG, "Not signed in; skipping photo upload")
            return Result.success()
        }

        val photoSyncEnabled = prefs.photoSyncEnabledFlow.first()
        if (!photoSyncEnabled) {
            Log.d(TAG, "Photo sync disabled in settings; skipping photo upload")
            return Result.success()
        }

        val qualitySetting = prefs.photoUploadQualityFlow.first()

        // 2. Fetch rows that are synced, not deleted, with local file, needing upload
        val couponsNeedingUpload = repository.getCouponsNeedingPhotoUpload()
        if (couponsNeedingUpload.isEmpty()) {
            return Result.success()
        }

        Log.d(TAG, "Found ${couponsNeedingUpload.size} coupon photos needing upload")
        var hadAnyFailure = false
        var uploadedAny = false

        for (coupon in couponsNeedingUpload) {
            val localFile = File(coupon.imagePath)
            if (!localFile.exists() || !localFile.canRead()) {
                Log.w(TAG, "Local file for coupon ${coupon.syncId} does not exist at ${coupon.imagePath}")
                continue
            }

            markUploading(coupon.syncId, true)
            try {
                // Prepare exact bytes to upload and variant tag ('original' or 'reduced')
                // Note: local original file is NEVER modified or deleted
                val (bytes, variant) = PhotoSyncHelper.preparePhotoBytes(localFile, qualitySetting)
                val sha256 = PhotoSyncHelper.computeSha256(bytes)

                val uploadResult = client.uploadPhoto(
                    userId = userId,
                    syncId = coupon.syncId,
                    imageBytes = bytes,
                    expectedSha256 = sha256
                )

                if (uploadResult.isSuccess) {
                    val remotePath = uploadResult.getOrThrow()
                    // Store remoteImagePath, imageSha256, imageVariant locally and mark row dirty
                    repository.updatePhotoUploadSuccess(
                        syncId = coupon.syncId,
                        remoteImagePath = remotePath,
                        imageSha256 = sha256,
                        imageVariant = variant
                    )
                    uploadedAny = true
                    Log.d(TAG, "Uploaded photo for ${coupon.syncId} ($variant) to $remotePath")
                } else {
                    val err = uploadResult.exceptionOrNull()
                    Log.e(TAG, "Upload failed for ${coupon.syncId}: ${err?.message}")
                    hadAnyFailure = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error uploading photo for ${coupon.syncId}: ${e.message}", e)
                hadAnyFailure = true
            } finally {
                markUploading(coupon.syncId, false)
            }
        }

        // If any photos were successfully uploaded and marked dirty, trigger sync push so metadata syncs
        if (uploadedAny) {
            syncManager.pushNowImmediately()
        }

        return if (hadAnyFailure) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
