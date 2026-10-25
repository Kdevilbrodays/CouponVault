package com.example.couponvault.util

import java.net.URI

object CodeExtractor {
    private val ALPHANUMERIC_REGEX = Regex("^[A-Za-z0-9]{8,24}$")

    /**
     * Extracts coupon code according to Phase 1 specification:
     * - Trim the input.
     * - If it parses as a URL whose host ends with "havells.com", return the last non-empty path segment, uppercased.
     * - Else if the whole string matches ^[A-Za-z0-9]{8,24}$, return it uppercased.
     * - Otherwise return null (ignore non-coupon QR codes).
     */
    fun extractCode(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val uri = tryParseUri(trimmed)
        if (uri != null) {
            val host = uri.host?.lowercase()
            if (host != null && (host == "havells.com" || host.endsWith(".havells.com") || host.endsWith("havells.com"))) {
                val path = uri.path ?: ""
                val segments = path.split('/').filter { it.isNotEmpty() }
                val lastSegment = segments.lastOrNull()
                if (!lastSegment.isNullOrBlank()) {
                    return lastSegment.uppercase()
                }
            }
        }

        if (ALPHANUMERIC_REGEX.matches(trimmed)) {
            return trimmed.uppercase()
        }

        return null
    }

    private fun tryParseUri(input: String): URI? {
        return try {
            val uri = URI(input)
            if (uri.isAbsolute && uri.host != null) {
                uri
            } else if (input.contains("havells.com", ignoreCase = true)) {
                val withScheme = if (!input.startsWith("http://", ignoreCase = true) && !input.startsWith("https://", ignoreCase = true)) {
                    "https://$input"
                } else input
                val fixedUri = URI(withScheme)
                if (fixedUri.host != null) fixedUri else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
