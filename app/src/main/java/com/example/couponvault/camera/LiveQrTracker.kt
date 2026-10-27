package com.example.couponvault.camera

import android.graphics.RectF
import com.example.couponvault.util.CodeExtractor

sealed interface LiveQrIndicator {
    object Searching : LiveQrIndicator
    data class MoveCloser(val box: RectF) : LiveQrIndicator
    data class NotCoupon(val box: RectF) : LiveQrIndicator
    data class Scannable(
        val box: RectF,
        val rawValue: String,
        val code: String
    ) : LiveQrIndicator
}

/**
 * State tracker for the live camera viewfinder indicator:
 * - Searching: thin neutral frame and "Point at a coupon QR"
 * - MoveCloser: when bounding box is narrower than ~15% of preview width
 * - NotCoupon: when QR is detected but extractCode() returns null
 * - Scannable: corner brackets snap onto detected QR's bounding box in sage green,
 *   only after seen in 2 consecutive analyses (anti-flicker), triggering light haptic once.
 * - Clears highlight after 400ms without detection.
 */
class LiveQrTracker(
    private val previewWidthProvider: () -> Float,
    private val onNewCodeScannable: (String) -> Unit = {}
) {
    private var lastDetectionTime: Long = 0L
    private var lastDetectedCode: String? = null
    private var consecutiveCodeHits: Int = 0
    private val hapticFiredCodes = mutableSetOf<String>()

    var currentIndicator: LiveQrIndicator = LiveQrIndicator.Searching
        private set

    fun onAnalysisResult(
        barcodes: List<BarcodeItem>,
        currentTimeMs: Long = System.currentTimeMillis()
    ): LiveQrIndicator {
        if (barcodes.isEmpty()) {
            if (currentTimeMs - lastDetectionTime > 400L) {
                currentIndicator = LiveQrIndicator.Searching
                consecutiveCodeHits = 0
                lastDetectedCode = null
            }
            return currentIndicator
        }

        val barcode = barcodes.first()
        val box = barcode.boundingBox?.let { RectF(it) } ?: RectF(0f, 0f, 0f, 0f)
        lastDetectionTime = currentTimeMs

        val previewWidth = previewWidthProvider()
        // QR too small (narrower than ~15% of preview width)
        if (previewWidth > 0 && box.width() < previewWidth * 0.15f) {
            consecutiveCodeHits = 0
            lastDetectedCode = null
            currentIndicator = LiveQrIndicator.MoveCloser(box)
            return currentIndicator
        }

        // Check if QR is a coupon code
        val extractedCode = CodeExtractor.extractCode(barcode.rawValue)
        if (extractedCode == null) {
            consecutiveCodeHits = 0
            lastDetectedCode = null
            currentIndicator = LiveQrIndicator.NotCoupon(box)
            return currentIndicator
        }

        // Valid coupon: check 2 consecutive hits
        if (extractedCode == lastDetectedCode) {
            consecutiveCodeHits++
        } else {
            lastDetectedCode = extractedCode
            consecutiveCodeHits = 1
        }

        if (consecutiveCodeHits >= 2) {
            if (hapticFiredCodes.add(extractedCode)) {
                onNewCodeScannable(extractedCode)
            }
            currentIndicator = LiveQrIndicator.Scannable(box, barcode.rawValue, extractedCode)
        } else {
            if (currentIndicator !is LiveQrIndicator.Scannable) {
                currentIndicator = LiveQrIndicator.Searching
            }
        }

        return currentIndicator
    }

    fun reset() {
        currentIndicator = LiveQrIndicator.Searching
        consecutiveCodeHits = 0
        lastDetectedCode = null
        lastDetectionTime = 0L
    }
}
