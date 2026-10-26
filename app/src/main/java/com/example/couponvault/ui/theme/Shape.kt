package com.example.couponvault.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// 14dp cards, 12dp fields and buttons, full pills for chips and bubble
val CardShape = RoundedCornerShape(14.dp)
val FieldShape = RoundedCornerShape(12.dp)
val ButtonShape = RoundedCornerShape(12.dp)
val PillShape = CircleShape

val Shapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = FieldShape,
    large = CardShape,
    extraLarge = PillShape
)
