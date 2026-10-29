package com.example.couponvault.sync

import android.content.Context
import android.util.Log
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class PhotoDownloadManager(
    private val context: Context,
    private val repository: CouponRepository,
    private val client: SupabaseClient
) {
    companion object {
        private const val TAG = "PhotoDownloadManager"
    }

    private val _downloadingSyncIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingSyncIds: StateFlow<Set<String>> = _downloadingSyncIds.asStateFlow()

    private val _isDownloadingAll = MutableStateFlow(false)
    val isDownloadingAll: StateFlow<Boolean> = _isDownloadingAll.asStateFlow()

    private val _downloadAllProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val downloadAllProgress: StateFlow<Pair<Int, Int>?> = _downloadAllProgress.asStateFlow()

    private val downloadMutex = Mutex()

    /**
     * Lazily downloads the remote photo for [coupon].
     * Verifies SHA-256 against coupon.imageSha256; if mismatch occurs, discards bytes and fails so it can retry later.
     * Saves photo into context.filesDir/originals/coupon_<UUID>.jpg and updates local imagePath.
     * Never writes to MediaStore.
     */
    suspend fun downloadPhoto(coupon: Coupon): Result<File> = withContext(Dispatchers.IO) {
        val remotePath = coupon.remoteImagePath
        if (remotePath.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No remote image path for coupon ${coupon.syncId}"))
        }

        // Avoid concurrent downloads of the same coupon
        downloadMutex.withLock {
            _downloadingSyncIds.value = _downloadingSyncIds.value + coupon.syncId
        }

        try {
            val downloadResult = client.downloadPhoto(remotePath)
            if (downloadResult.isFailure) {
                val err = downloadResult.exceptionOrNull() ?: IOException("Failed to download photo")
                Log.e(TAG, "Download failed for ${coupon.syncId}: ${err.message}")
                return@withContext Result.failure(err)
            }

            val bytes = downloadResult.getOrThrow()
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IOException("Downloaded empty photo file"))
            }

            // Verify SHA-256 against stored hash
            if (!PhotoSyncHelper.verifySha256(bytes, coupon.imageSha256)) {
                val computedHash = PhotoSyncHelper.computeSha256(bytes)
                Log.e(TAG, "Hash mismatch for ${coupon.syncId}! Expected: ${coupon.imageSha256}, Computed: $computedHash. Discarding download.")
                return@withContext Result.failure(
                    SecurityException("Photo hash mismatch: Expected ${coupon.imageSha256}, got $computedHash")
                )
            }

            // Create target file in filesDir/originals/
            val targetFile = StorageHelper.createOriginalFile(context)
            targetFile.outputStream().use { out ->
                out.write(bytes)
            }

            // Update database with local path
            repository.updateLocalImagePath(coupon.syncId, targetFile.absolutePath)
            Log.d(TAG, "Successfully downloaded photo for ${coupon.syncId} to ${targetFile.absolutePath}")

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing download for ${coupon.syncId}: ${e.message}", e)
            Result.failure(e)
        } finally {
            downloadMutex.withLock {
                _downloadingSyncIds.value = _downloadingSyncIds.value - coupon.syncId
            }
        }
    }

    /**
     * Downloads all missing photos for synced coupons that have a remoteImagePath but no local file.
     * Updates downloadAllProgress for UI display.
     */
    suspend fun downloadAllPhotos(onProgress: ((current: Int, total: Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        _isDownloadingAll.value = true
        _downloadAllProgress.value = null
        var successCount = 0

        try {
            val needingDownload = repository.getCouponsNeedingPhotoDownload()
            val total = needingDownload.size
            if (total == 0) {
                return@withContext 0
            }

            _downloadAllProgress.value = Pair(0, total)
            onProgress?.invoke(0, total)

            needingDownload.forEachIndexed { index, coupon ->
                val current = index + 1
                _downloadAllProgress.value = Pair(current, total)
                onProgress?.invoke(current, total)

                val result = downloadPhoto(coupon)
                if (result.isSuccess) {
                    successCount++
                }
            }
        } finally {
            _isDownloadingAll.value = false
            _downloadAllProgress.value = null
        }

        successCount
    }
}
