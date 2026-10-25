package com.example.couponvault.util

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.couponvault.data.Coupon
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
     * Copies an image from a content Uri (e.g. system Photo Picker) into private filesDir/originals/
     */
    fun copyUriToPrivateOriginal(context: Context, sourceUri: Uri): File {
        val targetFile = createOriginalFile(context)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open picked image stream")
        return targetFile
    }

    /**
     * Creates a delete request for gallery originals on Android 11+ (API 30+)
     * Returns a PendingIntent to show the system confirmation dialog.
     */
    fun createGalleryDeleteRequest(contentResolver: ContentResolver, uris: List<Uri>): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(contentResolver, uris)
        } else {
            null
        }
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
     * Works on Android 10+ (API 29+) without requiring storage permissions.
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

    /**
     * Generates CSV text for coupons: code,status,source,createdAt,redeemedAt,filename
     */
    fun generateCsvContent(coupons: List<Coupon>): String {
        val sb = StringBuilder()
        sb.append("code,status,source,createdAt,redeemedAt,filename\n")
        for (coupon in coupons) {
            val code = coupon.code ?: ""
            val status = coupon.status.name
            val source = coupon.source.name
            val createdAt = coupon.createdAt.toString()
            val redeemedAt = coupon.redeemedAt?.toString() ?: ""
            val filename = File(coupon.imagePath).name
            sb.append("\"$code\",$status,$source,$createdAt,$redeemedAt,\"$filename\"\n")
        }
        return sb.toString()
    }

    /**
     * Creates an Intent to share codes as a CSV file attachment.
     */
    fun createShareCsvIntent(context: Context, coupons: List<Coupon>): Intent {
        val csvContent = generateCsvContent(coupons)
        val cacheFile = File(context.cacheDir, "coupon_codes_${System.currentTimeMillis()}.csv")
        cacheFile.writeText(csvContent)

        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, cacheFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "CouponVault Export - ${coupons.size} codes")
            putExtra(Intent.EXTRA_TEXT, "Exported ${coupons.size} coupons from CouponVault.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Exports all originals and codes.csv to the chosen document tree folder using SAF.
     */
    fun exportAllToTreeFolder(
        context: Context,
        treeUri: Uri,
        coupons: List<Coupon>,
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<Int> = runCatching {
        val resolver = context.contentResolver
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

        // Find unique image files
        val uniqueFiles = coupons.map { File(it.imagePath) }.filter { it.exists() }.distinctBy { it.absolutePath }
        val totalItems = uniqueFiles.size + 1 // +1 for codes.csv

        var current = 0
        for (file in uniqueFiles) {
            val newFileUri = DocumentsContract.createDocument(
                resolver,
                treeDocUri,
                "image/jpeg",
                file.name
            ) ?: error("Could not create destination file for ${file.name}")

            resolver.openOutputStream(newFileUri)?.use { out ->
                FileInputStream(file).use { input ->
                    input.copyTo(out)
                }
            }
            current++
            onProgress(current, totalItems)
        }

        // Write codes.csv
        val csvUri = DocumentsContract.createDocument(
            resolver,
            treeDocUri,
            "text/csv",
            "codes.csv"
        ) ?: error("Could not create codes.csv")

        resolver.openOutputStream(csvUri)?.use { out ->
            out.write(generateCsvContent(coupons).toByteArray())
        }
        current++
        onProgress(current, totalItems)

        uniqueFiles.size
    }

    fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
