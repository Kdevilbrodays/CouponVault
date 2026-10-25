package com.example.couponvault.camera

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BarcodeItem(
    val rawValue: String,
    val boundingBox: Rect?
)

object BarcodeScannerHelper {

    // Configure QR code format only
    private val qrScannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(qrScannerOptions)

    /**
     * Scans an image file for QR codes using the bundled offline ML Kit scanner.
     * Returns a list of raw barcode string values found in the image.
     */
    suspend fun scanQrCodes(context: Context, imageFile: File): List<String> =
        scanBarcodesWithBounds(context, imageFile).map { it.rawValue }

    /**
     * Scans an image file for QR codes, returning raw values along with their bounding box.
     */
    suspend fun scanBarcodesWithBounds(context: Context, imageFile: File): List<BarcodeItem> =
        suspendCancellableCoroutine { continuation ->
            try {
                val inputImage = InputImage.fromFilePath(context, Uri.fromFile(imageFile))
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        val results = barcodes.mapNotNull { barcode ->
                            val raw = barcode.rawValue ?: barcode.displayValue
                            if (raw != null) {
                                BarcodeItem(rawValue = raw, boundingBox = barcode.boundingBox)
                            } else null
                        }
                        if (continuation.isActive) {
                            continuation.resume(results)
                        }
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(exception)
                        }
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
}
