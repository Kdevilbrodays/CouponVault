package com.example.couponvault.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couponvault.ui.theme.BrickError
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.DarkBrickError
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily

@Composable
fun DeleteRedeemedDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val brickColor = if (isDark) DarkBrickError else BrickError

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = CardShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Delete redeemed coupons?",
                fontFamily = TitleFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = "Delete $count redeemed coupons? Their photos will be deleted too. This can't be undone.",
                fontFamily = UiFontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = ButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = brickColor,
                    contentColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.testTag("dialog_confirm_delete_redeemed")
            ) {
                Text(
                    text = "Delete",
                    fontFamily = UiFontFamily,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_cancel_delete_redeemed")
            ) {
                Text(
                    text = "Cancel",
                    fontFamily = UiFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
