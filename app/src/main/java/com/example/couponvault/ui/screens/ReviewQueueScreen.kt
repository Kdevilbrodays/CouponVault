package com.example.couponvault.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.theme.BrickError
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.CodeFontFamily
import com.example.couponvault.ui.theme.CouponVaultTheme
import com.example.couponvault.ui.theme.FieldShape
import com.example.couponvault.ui.theme.PillShape
import com.example.couponvault.ui.theme.StatusRedeemedText
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewQueueScreen(
    viewModel: CouponViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val reviewList by viewModel.needsReviewCoupons.collectAsStateWithLifecycle()

    var currentIndex by remember { mutableIntStateOf(0) }

    // Clamp index when list size changes
    LaunchedEffect(reviewList.size) {
        if (currentIndex >= reviewList.size && reviewList.isNotEmpty()) {
            currentIndex = reviewList.size - 1
        }
    }

    if (reviewList.isEmpty()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Review Queue",
                            fontFamily = TitleFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = StatusRedeemedText,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "All caught up",
                        fontFamily = TitleFontFamily,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "No coupons need manual review right now.",
                        fontFamily = UiFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = onNavigateBack,
                        shape = ButtonShape,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(
                            text = "Back to coupons",
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        return
    }

    val currentCoupon = reviewList.getOrNull(currentIndex) ?: reviewList.first()

    // Form inputs
    var codeInput by remember(currentCoupon.id) {
        mutableStateOf(currentCoupon.code ?: "")
    }
    var errorMessage by remember(currentCoupon.id) { mutableStateOf<String?>(null) }

    // Pinch-to-zoom states
    var scale by remember(currentCoupon.id) { mutableFloatStateOf(1f) }
    var offset by remember(currentCoupon.id) { mutableStateOf(Offset.Zero) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("review_queue_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Review (${currentIndex + 1} of ${reviewList.size})",
                                fontFamily = TitleFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Type the code printed under the QR",
                                fontFamily = UiFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.Close, contentDescription = "Close queue")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                LinearProgressIndicator(
                    progress = { (currentIndex + 1).toFloat() / reviewList.size.toFloat() },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Photo on paper mat with hairline border
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.05f))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(currentCoupon.imagePath))
                            .crossfade(true)
                            .build(),
                        contentDescription = "Coupon Photo for Review",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentCoupon.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    val maxPanX = (size.width * (scale - 1f)) / 2f
                                    val maxPanY = (size.height * (scale - 1f)) / 2f
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                        y = (offset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                    )
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )

                    // Zoom reset
                    if (scale > 1.05f) {
                        Surface(
                            shape = PillShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                                .clickable {
                                    scale = 1f
                                    offset = Offset.Zero
                                }
                        ) {
                            Text(
                                text = "Reset (${String.format("%.1f", scale)}x)",
                                fontFamily = UiFontFamily,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Code Entry and Approval Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "COUPON CODE",
                        fontFamily = UiFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )

                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it.uppercase()
                            errorMessage = null
                        },
                        label = { Text("Code", fontFamily = UiFontFamily) },
                        placeholder = { Text("e.g. A51PEWKCWB81MXN", fontFamily = CodeFontFamily) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = CodeFontFamily,
                            fontWeight = FontWeight.SemiBold
                        ),
                        singleLine = true,
                        isError = errorMessage != null,
                        supportingText = {
                            if (errorMessage != null) {
                                Text(errorMessage!!, fontFamily = UiFontFamily, color = BrickError)
                            } else {
                                Text(
                                    "No code found. Type it from the printed text under the QR.",
                                    fontFamily = UiFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingIcon = {
                            if (codeInput.isNotEmpty()) {
                                IconButton(onClick = { codeInput = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear text")
                                }
                            }
                        },
                        shape = FieldShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                viewModel.saveManualCode(currentCoupon, codeInput) { success, msg ->
                                    if (success) {
                                        Toast.makeText(context, "Saved as Unscanned", Toast.LENGTH_SHORT).show()
                                    } else {
                                        errorMessage = msg
                                    }
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("review_code_input")
                    )

                    // Approve Button: One prominent clay primary button
                    Button(
                        onClick = {
                            viewModel.saveManualCode(currentCoupon, codeInput) { success, msg ->
                                if (success) {
                                    Toast.makeText(context, "Saved as Unscanned", Toast.LENGTH_SHORT).show()
                                } else {
                                    errorMessage = msg
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("approve_code_button"),
                        shape = ButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save code",
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Quiet Secondary Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.updateStatus(currentCoupon, CouponStatus.FAILED)
                                Toast.makeText(context, "Marked as Failed", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = ButtonShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = "Mark failed",
                                fontFamily = UiFontFamily,
                                fontSize = 13.sp,
                                color = BrickError,
                                softWrap = false
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.deleteCoupon(currentCoupon) {
                                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = ButtonShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = "Delete",
                                fontFamily = UiFontFamily,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // Queue Navigation controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (currentIndex > 0) currentIndex--
                    },
                    enabled = currentIndex > 0,
                    shape = ButtonShape
                ) {
                    Icon(Icons.Default.NavigateBefore, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Previous", fontFamily = UiFontFamily, softWrap = false)
                }

                Text(
                    text = "${currentIndex + 1} of ${reviewList.size}",
                    fontFamily = UiFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = {
                        if (currentIndex < reviewList.size - 1) currentIndex++
                    },
                    enabled = currentIndex < reviewList.size - 1,
                    shape = ButtonShape
                ) {
                    Text("Skip", fontFamily = UiFontFamily, softWrap = false)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.NavigateNext, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(name = "Review - Light 360dp", widthDp = 360, fontScale = 1.3f)
@Composable
private fun ReviewQueuePreviewLight() {
    CouponVaultTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "COUPON CODE",
                            fontFamily = UiFontFamily,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = "A51PEWKCWB81MXN",
                            onValueChange = {},
                            label = { Text("Code") },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = CodeFontFamily),
                            shape = FieldShape,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {},
                            shape = ButtonShape,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Save code", fontFamily = UiFontFamily)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Review - Dark 360dp", widthDp = 360, fontScale = 1.3f)
@Composable
private fun ReviewQueuePreviewDark() {
    CouponVaultTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "COUPON CODE",
                            fontFamily = UiFontFamily,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = "B99QRXCZLA01",
                            onValueChange = {},
                            label = { Text("Code") },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = CodeFontFamily),
                            shape = FieldShape,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {},
                            shape = ButtonShape,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Save code", fontFamily = UiFontFamily)
                        }
                    }
                }
            }
        }
    }
}
