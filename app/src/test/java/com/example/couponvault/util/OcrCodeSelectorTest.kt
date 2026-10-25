package com.example.couponvault.util

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrCodeSelectorTest {

    @Test
    fun testExtractCandidatesRegex() {
        val sampleText = "Scan QR or enter code: A51PEWKCWB81MXN under barcode. Serial: 12345, Version: 1.0"
        val candidates = OcrCodeSelector.extractCandidatesFromText(sampleText)

        assertEquals(1, candidates.size)
        assertEquals("A51PEWKCWB81MXN", candidates.first().token)
    }

    @Test
    fun testRejectShortAndInvalidTokens() {
        val invalidText = "Havells India Ltd short code ABC1234 123456789 (9 chars) valid: B51PEWKCWB81MXN2"
        val candidates = OcrCodeSelector.extractCandidatesFromText(invalidText)

        // ABC1234 is 7 chars (too short), 123456789 is 9 chars (too short)
        // B51PEWKCWB81MXN2 is 16 chars (valid)
        assertEquals(1, candidates.size)
        assertEquals("B51PEWKCWB81MXN2", candidates.first().token)
    }

    @Test
    fun testPreferTokenNearestBelowBarcode() {
        // Barcode at (100, 100, 300, 300) -> top=100, bottom=300, center=(200, 200)
        val barcodeBox = Rect(100, 100, 300, 300)

        // Token 1 above barcode: (100, 50, 300, 80)
        val candidateAbove = OcrTokenCandidate(
            token = "HEADERTOKEN12345",
            boundingBox = Rect(100, 50, 300, 80)
        )

        // Token 2 far below barcode: (100, 600, 300, 630)
        val candidateFarBelow = OcrTokenCandidate(
            token = "FOOTERTOKEN12345",
            boundingBox = Rect(100, 600, 300, 630)
        )

        // Token 3 directly below barcode: (100, 320, 300, 350)
        val candidateDirectlyBelow = OcrTokenCandidate(
            token = "A51PEWKCWB81MXN",
            boundingBox = Rect(100, 320, 300, 350)
        )

        val candidates = listOf(candidateAbove, candidateFarBelow, candidateDirectlyBelow)
        val selected = OcrCodeSelector.selectBestOcrToken(candidates, barcodeBox)

        assertEquals("A51PEWKCWB81MXN", selected)
    }

    @Test
    fun testBestCandidateFallbackWithoutBarcode() {
        // Without barcode box, mixed letter/digit coupon token of typical length (14-16) wins over pure digits
        val candidateDigits = OcrTokenCandidate(token = "12345678901234")
        val candidateCoupon = OcrTokenCandidate(token = "A51PEWKCWB81MXN")

        val selected = OcrCodeSelector.selectBestOcrToken(listOf(candidateDigits, candidateCoupon), barcodeBox = null)
        assertEquals("A51PEWKCWB81MXN", selected)
    }

    @Test
    fun testEmptyCandidatesReturnsNull() {
        assertNull(OcrCodeSelector.selectBestOcrToken(emptyList()))
    }
}
