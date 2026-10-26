package com.example.couponvault.ui.theme

import androidx.compose.ui.graphics.Color

// --- Light Paper Palette ---
val PaperBackground = Color(0xFFF4F1EA)
val PaperSurface = Color(0xFFFBF9F4)
val PaperSurfaceVariant = Color(0xFFECE8DC)
val PaperOutline = Color(0xFFDDD8C8)
val PaperOnBackground = Color(0xFF1F1D1A)
val PaperOnSurfaceVariant = Color(0xFF5E5A52)
val ClayPrimary = Color(0xFFB5532F)
val ClayOnPrimary = Color(0xFFFFFFFF)

// --- Dark Warm Palette (Warm, never navy or pure black) ---
val DarkPaperBackground = Color(0xFF1F1E1C)
val DarkPaperSurface = Color(0xFF2A2825)
val DarkPaperSurfaceVariant = Color(0xFF34312D)
val DarkPaperOutline = Color(0xFF46423B)
val DarkPaperOnBackground = Color(0xFFECE8DE)
val DarkPaperOnSurfaceVariant = Color(0xFFB0AB9F)
val DarkClayPrimary = Color(0xFFE0805C)
val DarkClayOnPrimary = Color(0xFF2A1208)

// --- Semantic Status Pills (Tinted container + text, no border) ---
// Light
val StatusUnscannedContainerLight = Color(0xFFF1DFD2)
val StatusUnscannedTextLight = Color(0xFF7A3A1E)
val StatusRedeemedContainerLight = Color(0xFFDDE5D3)
val StatusRedeemedTextLight = Color(0xFF3F5A2E)
val StatusFailedContainerLight = Color(0xFFF1D6D2)
val StatusFailedTextLight = Color(0xFF8A2E25)
val StatusReviewContainerLight = Color(0xFFF0E2BC)
val StatusReviewTextLight = Color(0xFF6B4E0E)

// Dark
val StatusUnscannedContainerDark = Color(0xFF3B2418)
val StatusUnscannedTextDark = Color(0xFFF1DFD2)
val StatusRedeemedContainerDark = Color(0xFF22331C)
val StatusRedeemedTextDark = Color(0xFFDDE5D3)
val StatusFailedContainerDark = Color(0xFF3D1F1C)
val StatusFailedTextDark = Color(0xFFF1D6D2)
val StatusReviewContainerDark = Color(0xFF3B301A)
val StatusReviewTextDark = Color(0xFFF0E2BC)

// --- Aliases for ergonomic usage ---
val PrimaryClay = ClayPrimary
val BrickError = Color(0xFF8A2E25)
val DarkBrickError = Color(0xFFE06D60)
val RoseError = BrickError
val EmeraldGreen = StatusRedeemedTextLight

val StatusUnscannedBg = StatusUnscannedContainerLight
val StatusUnscannedText = StatusUnscannedTextLight
val StatusRedeemedBg = StatusRedeemedContainerLight
val StatusRedeemedText = StatusRedeemedTextLight
val StatusNeedsReviewBg = StatusReviewContainerLight
val StatusNeedsReviewText = StatusReviewTextLight
val StatusFailedBg = StatusFailedContainerLight
val StatusFailedText = StatusFailedTextLight
