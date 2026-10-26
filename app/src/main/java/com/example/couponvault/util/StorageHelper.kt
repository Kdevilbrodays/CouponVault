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
import android.content.ContentUris
import android.provider.OpenableColumns
import android.util.Log

object StorageHelper {
    private const val DIRECTORY_NAME = "originals"
    private const val TAG = "StorageHelper"

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
     * Generates a new file path with a unique UUID for saving a full-resolution original JPEG.
     * Uses UUIDs so photos imported in the same millisecond cannot overwrite each other.
     */
    fun createOriginalFile(context: Context): File {
        val fileName = "coupon_${UUID.randomUUID()}.jpg"
        return File(getOriginalsDirectory(context), fileName)
    }

    /**
     * Converts a content URI (such as a document URI from OpenMultipleDocuments)
     * to a MediaStore URI suitable for MediaStore.createDeleteRequest().
     *
     * Handles:
     * - Direct MediaStore URIs (authority is MediaStore.AUTHORITY or media images)
     * - API 31+ MediaStore.getMediaUri()
     * - Document provider URIs (authority "com.android.providers.media.documents")
     *   with document ID format "image:<id>"
     * Returns null if the URI cannot be converted.
     */
    fun convertToMediaStoreUri(context: Context, uri: Uri): Uri? {
        // If it's already a MediaStore URI
        if (uri.authority == MediaStore.AUTHORITY || (uri.authority == "media" && uri.path?.contains("images") == true)) {
            return uri
        }

        // On API 31+, try MediaStore.getMediaUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val mediaUri = MediaStore.getMediaUri(context, uri)
                if (mediaUri != null) {
                    return mediaUri
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore.getMediaUri failed for $uri", e)
            }
        }

        // Document provider fallback
        if (uri.authority == "com.android.providers.media.documents") {
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                val idPart = if (docId.contains(":")) {
                    docId.substringAfter(":")
                } else {
                    docId
                }
                val mediaId = idPart.toLongOrNull()
                if (mediaId != null) {
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "DocumentsContract.getDocumentId failed for $uri", e)
            }
        }

        return null
    }

    /**
     * Queries the byte size of a content URI via AssetFileDescriptor or OpenableColumns.
     */
    fun getSourceByteSize(context: Context, uri: Uri): Long? {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val len = afd.length
                if (len > 0L && len != android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) {
                    return len
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed openAssetFileDescriptor for $uri", e)
        }

        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        val size = cursor.getLong(sizeIndex)
                        if (size > 0L) return size
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed query OpenableColumns.SIZE for $uri", e)
        }

        return null
    }

    /**
     * Verifies that the private copy exists in filesDir/originals and its byte size matches the expected size.
     */
    fun verifyCopy(destinationFile: File, expectedByteSize: Long?): Boolean {
        if (!destinationFile.exists() || !destinationFile.isFile) {
            return false
        }
        val destSize = destinationFile.length()
        if (destSize <= 0L) {
            return false
        }
        if (expectedByteSize != null && destSize != expectedByteSize) {
            return false
        }
        return true
    }

    /**
     * Overload to verify copy using context and source URI.
     */
    fun verifyCopy(context: Context, sourceUri: Uri, destinationFile: File, cachedSourceSize: Long? = null): Boolean {
        val expectedSize = cachedSourceSize ?: getSourceByteSize(context, sourceUri)
        return verifyCopy(destinationFile, expectedSize)
    }

    /**
     * Copies an image from a content Uri into private filesDir/originals/ using a UUID filename.
     * Verifies byte size after copy; deletes the file and throws an exception on mismatch.
     */
    fun copyUriToPrivateOriginal(context: Context, sourceUri: Uri): File {
        val targetFile = createOriginalFile(context)
        try {
            val bytesWritten = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open input stream for $sourceUri")

            if (!targetFile.exists() || targetFile.length() <= 0L || targetFile.length() != bytesWritten) {
                targetFile.delete()
                error("File copy verification failed for $sourceUri: size mismatch or empty")
            }
            return targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy $sourceUri to private storage", e)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            throw e
        }
    }

    /**
     * Creates a delete request for gallery originals on Android 11+ (API 30+).
     * Guards against empty lists, API < 30, and catches any MediaStore exceptions.
     */
    fun createGalleryDeleteRequest(contentResolver: ContentResolver, uris: List<Uri>): PendingIntent? {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return try {
            MediaStore.createDeleteRequest(contentResolver, uris)
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore.createDeleteRequest failed", e)
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
