package com.example.couponvault.camera

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveQrTrackerTest {

    @Test
    fun testTwoConsecutiveFramesRequiredForScannable() {
        val hapticCodes = mutableListOf<String>()
        val tracker = LiveQrTracker(
            previewWidthProvider = { 1000f },
            onNewCodeScannable = { hapticCodes.add(it) }
        )

        val qrItem = BarcodeItem(
            rawValue = "https://havells.com/c/A51PEWKCWB81MXN",
            boundingBox = Rect(200, 200, 500, 500) // width = 300 (30% of 1000, >15%)
        )

        // Frame 1: First hit must NOT snap to Scannable yet (anti-flicker protection)
        val state1 = tracker.onAnalysisResult(listOf(qrItem), currentTimeMs = 1000L)
        assertTrue(
            "First frame should remain Searching or non-scannable to prevent flicker",
            state1 is LiveQrIndicator.Searching
        )
        assertTrue("No haptic on first frame", hapticCodes.isEmpty())

        // Frame 2: Second consecutive hit with same code MUST snap to Scannable
        val state2 = tracker.onAnalysisResult(listOf(qrItem), currentTimeMs = 1150L)
        assertTrue("Second consecutive frame must be Scannable", state2 is LiveQrIndicator.Scannable)
        val scannable = state2 as LiveQrIndicator.Scannable
        assertEquals("A51PEWKCWB81MXN", scannable.code)
        assertEquals("A51PEWKCWB81MXN", hapticCodes.singleOrNull())

        // Frame 3: Third consecutive hit with same code stays Scannable, no duplicate haptic
        val state3 = tracker.onAnalysisResult(listOf(qrItem), currentTimeMs = 1300L)
        assertTrue("Stays Scannable", state3 is LiveQrIndicator.Scannable)
        assertEquals("Haptic must fire only once per new code", 1, hapticCodes.size)
    }

    @Test
    fun testSmallQrShowsMoveCloser() {
        val tracker = LiveQrTracker(
            previewWidthProvider = { 1000f }
        )

        // Bounding box width = 100px (< 15% of 1000 = 150px)
        val smallQr = BarcodeItem(
            rawValue = "https://havells.com/c/A51PEWKCWB81MXN",
            boundingBox = Rect(100, 100, 200, 200)
        )

        val state = tracker.onAnalysisResult(listOf(smallQr), currentTimeMs = 1000L)
        assertTrue("Small QR must trigger MoveCloser hint", state is LiveQrIndicator.MoveCloser)
    }

    @Test
    fun testNonCouponQrShowsNotCoupon() {
        val tracker = LiveQrTracker(
            previewWidthProvider = { 1000f }
        )

        // Standard URL or text that is not a Havells coupon and not 8-24 alphanumeric
        val nonCouponQr = BarcodeItem(
            rawValue = "https://example.com/menu",
            boundingBox = Rect(200, 200, 500, 500)
        )

        val state = tracker.onAnalysisResult(listOf(nonCouponQr), currentTimeMs = 1000L)
        assertTrue("Non-coupon QR must show NotCoupon hint", state is LiveQrIndicator.NotCoupon)
    }

    @Test
    fun testClearHighlightAfter400msWithoutDetection() {
        val tracker = LiveQrTracker(
            previewWidthProvider = { 1000f }
        )

        val qrItem = BarcodeItem(
            rawValue = "https://havells.com/c/A51PEWKCWB81MXN",
            boundingBox = Rect(200, 200, 500, 500)
        )

        // Get to Scannable
        tracker.onAnalysisResult(listOf(qrItem), currentTimeMs = 1000L)
        val scannableState = tracker.onAnalysisResult(listOf(qrItem), currentTimeMs = 1150L)
        assertTrue(scannableState is LiveQrIndicator.Scannable)

        // After 200ms without detection (< 400ms): highlight remains held
        val heldState = tracker.onAnalysisResult(emptyList(), currentTimeMs = 1350L)
        assertTrue("Highlight should not clear before 400ms", heldState is LiveQrIndicator.Scannable)

        // After 450ms without detection (> 400ms): highlight must clear to Searching
        val clearedState = tracker.onAnalysisResult(emptyList(), currentTimeMs = 1600L)
        assertTrue("Highlight must clear after 400ms without detection", clearedState is LiveQrIndicator.Searching)
    }
}
