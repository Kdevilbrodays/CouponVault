package com.example.couponvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.ui.theme.AmberWarning
import com.example.couponvault.ui.theme.AmberWarningContainer
import com.example.couponvault.ui.theme.AmberWarningOnContainer
import com.example.couponvault.ui.theme.EmeraldGreen
import com.example.couponvault.ui.theme.EmeraldGreenContainer
import com.example.couponvault.ui.theme.EmeraldGreenOnContainer
import com.example.couponvault.ui.theme.PrimaryBlue
import com.example.couponvault.ui.theme.RoseError
import com.example.couponvault.ui.theme.RoseErrorContainer
import com.example.couponvault.ui.theme.RoseErrorOnContainer

@Composable
fun StatusBadge(
    status: CouponStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, icon) = when (status) {
        CouponStatus.UNSCANNED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.QrCode
        )
        CouponStatus.REDEEMED -> Triple(
            EmeraldGreenContainer,
            EmeraldGreenOnContainer,
            Icons.Default.CheckCircle
        )
        CouponStatus.NEEDS_REVIEW -> Triple(
            AmberWarningContainer,
            AmberWarningOnContainer,
            Icons.Default.HelpOutline
        )
        CouponStatus.FAILED -> Triple(
            RoseErrorContainer,
            RoseErrorOnContainer,
            Icons.Default.ErrorOutline
        )
    }

    val label = when (status) {
        CouponStatus.UNSCANNED -> "Unscanned"
        CouponStatus.REDEEMED -> "Redeemed"
        CouponStatus.NEEDS_REVIEW -> "Needs Review"
        CouponStatus.FAILED -> "Failed"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
