package com.example.couponvault.util

import android.graphics.Rect
import kotlin.math.abs

data class OcrTokenCandidate(
    val token: String,
    val boundingBox: Rect? = null,
    val isBelowBarcode: Boolean = false,
    val distanceToBarcode: Double = Double.MAX_VALUE
)

object OcrCodeSelector {
    private val TOKEN_REGEX = Regex("^[A-Z0-9]{10,20}$")

    /**
     * Extracts potential alphanumeric coupon tokens from recognized text blocks/lines.
     * Tokens are uppercased and stripped of whitespace and invalid punctuation.
     * Matches ^[A-Z0-9]{10,20}$.
     */
    fun extractCandidatesFromText(text: String, boundingBox: Rect? = null): List<OcrTokenCandidate> {
        val candidates = mutableListOf<OcrTokenCandidate>()
        // Split by whitespace and common punctuation delimiters
        val words = text.split(Regex("[\\s,;:/|]+"))
        for (word in words) {
            val cleaned = word.uppercase().replace(Regex("[^A-Z0-9]"), "")
            if (TOKEN_REGEX.matches(cleaned)) {
                candidates.add(OcrTokenCandidate(token = cleaned, boundingBox = boundingBox))
            }
        }
        return candidates
    }

    /**
     * Selects the best OCR coupon token according to specification:
     * - Tokens must match ^[A-Z0-9]{10,20}$
     * - Prefer the token nearest below any detected barcode area
     * - Otherwise return the best candidate (preferring mixed letters+digits and typical coupon length)
     */
    fun selectBestOcrToken(
        candidates: List<OcrTokenCandidate>,
        barcodeBox: Rect? = null
    ): String? {
        if (candidates.isEmpty()) return null

        val distinctCandidates = candidates.distinctBy { it.token }

        if (barcodeBox != null) {
            // Find tokens located below the barcode bounding box
            val belowCandidates = distinctCandidates.mapNotNull { candidate ->
                val box = candidate.boundingBox
                if (box != null && box.top >= (barcodeBox.bottom - 10)) { // allow small margin
                    val verticalDistance = (box.top - barcodeBox.bottom).coerceAtLeast(0)
                    val horizontalOffset = abs(box.centerX() - barcodeBox.centerX())
                    // Weighted distance: vertical distance is primary, horizontal offset secondary
                    val totalDistance = verticalDistance.toDouble() + (horizontalOffset * 0.4)
                    candidate.copy(
                        isBelowBarcode = true,
                        distanceToBarcode = totalDistance
                    )
                } else {
                    null
                }
            }

            if (belowCandidates.isNotEmpty()) {
                // Return token with the smallest distance below the barcode
                return belowCandidates.minByOrNull { it.distanceToBarcode }?.token
            }
        }

        // Fallback: pick the best candidate among all tokens
        // Preference: contains both letters and digits, length close to 15 (standard Havells code length)
        return distinctCandidates.maxByOrNull { candidate ->
            var score = 0
            val token = candidate.token
            val hasLetters = token.any { it.isLetter() }
            val hasDigits = token.any { it.isDigit() }

            if (hasLetters && hasDigits) score += 20
            if (token.length in 14..16) score += 10
            score -= abs(token.length - 15) // small penalty for deviation from typical 15 chars
            score
        }?.token
    }
}
