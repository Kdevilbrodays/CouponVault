package com.example.couponvault.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object StorageHelper {
    private const val DIRECTORY_NAME = "originals"

    /**
     * Returns the private directory in context.filesDir/originals/
     */
    fun getOriginalsDirectory(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Generates a new file path for saving a full-resolution original JPEG.
     */
    fun createOriginalFile(context: Context): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val randomSuffix = UUID.randomUUID().toString().take(6)
        val fileName = "coupon_${timestamp}_$randomSuffix.jpg"
        return File(getOriginalsDirectory(context), fileName)
    }

    /**
     * Deletes a file only if it is no longer referenced by any coupon in the database.
     */
    fun deleteFileIfUnreferenced(filePath: String, referenceCount: Int): Boolean {
        if (referenceCount <= 0) {
            val file = File(filePath)
            if (file.exists()) {
                return file.delete()
            }
        }
        return false
    }

    /**
     * Copies the private original JPEG into MediaStore Pictures/CouponVault
     * Works on Android 10+ (API 29+) without requiring any storage permissions.
     */
    fun saveOriginalToGallery(context: Context, originalFile: File): Result<Uri> {
        return runCatching {
            if (!originalFile.exists()) {
                error("Original file does not exist")
            }

            val displayName = originalFile.name
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CouponVault")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: error("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(originalFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: error("Failed to open output stream for gallery image")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            uri
        }
    }

    /**
     * Creates a share intent using FileProvider for the original private image.
     */
    fun createShareIntent(context: Context, originalFile: File, code: String?): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, originalFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            val shareText = if (!code.isNullOrBlank()) {
                "Coupon Code: $code"
            } else {
                "Coupon Vault Photo"
            }
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, shareText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
