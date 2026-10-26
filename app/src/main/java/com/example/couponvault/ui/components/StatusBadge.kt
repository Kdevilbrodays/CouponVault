package com.example.couponvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.ui.theme.StatusFailedContainerDark
import com.example.couponvault.ui.theme.StatusFailedContainerLight
import com.example.couponvault.ui.theme.StatusFailedTextDark
import com.example.couponvault.ui.theme.StatusFailedTextLight
import com.example.couponvault.ui.theme.StatusRedeemedContainerDark
import com.example.couponvault.ui.theme.StatusRedeemedContainerLight
import com.example.couponvault.ui.theme.StatusRedeemedTextDark
import com.example.couponvault.ui.theme.StatusRedeemedTextLight
import com.example.couponvault.ui.theme.StatusReviewContainerDark
import com.example.couponvault.ui.theme.StatusReviewContainerLight
import com.example.couponvault.ui.theme.StatusReviewTextDark
import com.example.couponvault.ui.theme.StatusReviewTextLight
import com.example.couponvault.ui.theme.StatusUnscannedContainerDark
import com.example.couponvault.ui.theme.StatusUnscannedContainerLight
import com.example.couponvault.ui.theme.StatusUnscannedTextDark
import com.example.couponvault.ui.theme.StatusUnscannedTextLight
import com.example.couponvault.ui.theme.UiFontFamily

@Composable
fun StatusBadge(
    status: CouponStatus,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val (bgColor, textColor, label) = when (status) {
        CouponStatus.UNSCANNED -> Triple(
            if (isDark) StatusUnscannedContainerDark else StatusUnscannedContainerLight,
            if (isDark) StatusUnscannedTextDark else StatusUnscannedTextLight,
            "Unscanned"
        )
        CouponStatus.REDEEMED -> Triple(
            if (isDark) StatusRedeemedContainerDark else StatusRedeemedContainerLight,
            if (isDark) StatusRedeemedTextDark else StatusRedeemedTextLight,
            "Redeemed"
        )
        CouponStatus.NEEDS_REVIEW -> Triple(
            if (isDark) StatusReviewContainerDark else StatusReviewContainerLight,
            if (isDark) StatusReviewTextDark else StatusReviewTextLight,
            "Needs review"
        )
        CouponStatus.FAILED -> Triple(
            if (isDark) StatusFailedContainerDark else StatusFailedContainerLight,
            if (isDark) StatusFailedTextDark else StatusFailedTextLight,
            "Failed"
        )
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontFamily = UiFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
