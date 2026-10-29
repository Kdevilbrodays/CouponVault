package com.example.couponvault.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object PhotoSyncHelper {
    private const val TAG = "PhotoSyncHelper"
    const val VARIANT_ORIGINAL = "original"
    const val VARIANT_REDUCED = "reduced"
    const val REDUCED_MAX_DIMENSION = 2048
    const val REDUCED_JPEG_QUALITY = 85

    /**
     * Computes the lowercase hex SHA-256 hash of a byte array.
     */
    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the lowercase hex SHA-256 hash of a file without loading entire file into memory.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        val hash = digest.digest()
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies that the computed SHA-256 of downloaded or uploaded bytes matches the expected hash.
     * Returns true if expectedHash is null (no verification possible) or if hashes match case-insensitively.
     */
    fun verifySha256(bytes: ByteArray, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrBlank()) return true
        val computed = computeSha256(bytes)
        return computed.equals(expectedHash.trim(), ignoreCase = true)
    }

    /**
     * Resizes an image file to a maximum dimension of [maxDimension] preserving aspect ratio,
     * and compresses it to JPEG at [quality] percent.
     * The original file on disk is never modified.
     * Returns the compressed byte array, or null if decoding failed.
     */
    fun resizeImage(file: File, maxDimension: Int = REDUCED_MAX_DIMENSION, quality: Int = REDUCED_JPEG_QUALITY): ByteArray? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            val maxSide = maxOf(origWidth, origHeight)
            var sampleSize = 1
            while (maxSide / (sampleSize * 2) >= maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

            val currentMax = maxOf(decoded.width, decoded.height)
            val finalBitmap = if (currentMax > maxDimension) {
                val scale = maxDimension.toFloat() / currentMax.toFloat()
                val targetWidth = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (decoded.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                if (scaled != decoded) {
                    decoded.recycle()
                }
                scaled
            } else {
                decoded
            }

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            finalBitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize image: ${e.message}", e)
            null
        }
    }

    /**
     * Prepares the bytes to upload based on the requested quality setting ('original' or 'reduced').
     * If 'reduced' is selected, resizes to at most 2048px long side at JPEG 85.
     * Returns a Pair of exact bytes to upload and the variant tag ('original' or 'reduced').
     * Note: The local original file is NEVER modified or deleted.
     */
    fun preparePhotoBytes(file: File, qualitySetting: String): Pair<ByteArray, String> {
        if (qualitySetting.equals(VARIANT_REDUCED, ignoreCase = true)) {
            val reducedBytes = resizeImage(file, maxDimension = REDUCED_MAX_DIMENSION, quality = REDUCED_JPEG_QUALITY)
            if (reducedBytes != null && reducedBytes.isNotEmpty()) {
                return Pair(reducedBytes, VARIANT_REDUCED)
            }
        }
        return Pair(file.readBytes(), VARIANT_ORIGINAL)
    }
}
