package com.example.couponvault.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.couponvault.camera.BarcodeItem
import com.example.couponvault.camera.LiveQrIndicator
import com.example.couponvault.camera.LiveQrTracker
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.theme.BrickError
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.CodeFontFamily
import com.example.couponvault.ui.theme.CouponVaultTheme
import com.example.couponvault.ui.theme.FieldShape
import com.example.couponvault.ui.theme.PillShape
import com.example.couponvault.ui.theme.PrimaryClay
import com.example.couponvault.ui.theme.StatusNeedsReviewText
import com.example.couponvault.ui.theme.StatusRedeemedText
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

@Composable
fun CaptureScreen(
    viewModel: CouponViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // Document Picker launcher for importing photos (needs no storage permissions)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importGalleryPhotos(context, uris)
        }
    }

    // MediaStore gallery delete launcher (Android 11+) declared at top level
    val galleryDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleDeleteResult(result.resultCode)
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val onLaunchImport: () -> Unit = {
        importLauncher.launch(arrayOf("image/*"))
    }

    val onConfirmDelete: () -> Unit = {
        val pendingUris = viewModel.pendingDeleteUris.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pendingUris.isNotEmpty()) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, pendingUris)
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                galleryDeleteLauncher.launch(request)
            } catch (e: Exception) {
                viewModel.onDeleteException(e)
            }
        } else {
            viewModel.dismissDeleteGalleryDialog()
        }
    }

    if (hasCameraPermission) {
        CameraPreviewContent(
            viewModel = viewModel,
            onClose = onNavigateBack,
            onLaunchImport = onLaunchImport,
            onConfirmDelete = onConfirmDelete
        )
    } else {
        CameraPermissionRationale(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onClose = onNavigateBack
        )
    }
}

@Composable
fun CameraPreviewContent(
    viewModel: CouponViewModel,
    onClose: () -> Unit,
    onLaunchImport: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val sessionCount by viewModel.sessionPhotoCount.collectAsStateWithLifecycle()
    val sessionCoupons by viewModel.sessionCoupons.collectAsStateWithLifecycle()
    val lastCapturedCoupon by viewModel.lastCapturedCoupon.collectAsStateWithLifecycle()
    val captureFeedback by viewModel.captureFeedback.collectAsStateWithLifecycle()
    val lastCapturedImagePath by viewModel.lastCapturedImagePath.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()

    val showDeleteGalleryDialog by viewModel.showDeleteGalleryDialog.collectAsStateWithLifecycle()
    val pendingDeleteUris by viewModel.pendingDeleteUris.collectAsStateWithLifecycle()

    var isTorchEnabled by remember { mutableStateOf(false) }
    var showShutterFlash by remember { mutableStateOf(false) }

    // Live Viewfinder state
    var previewWidthPx by remember { mutableFloatStateOf(1080f) }
    var currentLiveIndicator by remember { mutableStateOf<LiveQrIndicator>(LiveQrIndicator.Searching) }

    val tracker = remember {
        LiveQrTracker(
            previewWidthProvider = { previewWidthPx },
            onNewCodeScannable = { _ ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        )
    }

    // Auto-dismiss the feedback banner after 2.4 seconds
    LaunchedEffect(captureFeedback) {
        if (captureFeedback != null) {
            delay(2400)
            viewModel.dismissFeedback()
        }
    }

    // Shutter flash animation auto-fadeout
    LaunchedEffect(showShutterFlash) {
        if (showShutterFlash) {
            delay(70)
            showShutterFlash = false
        }
    }

    // Clear live indicator after 400ms without detection
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(100)
            val now = System.currentTimeMillis()
            currentLiveIndicator = tracker.onAnalysisResult(emptyList(), now)
        }
    }

    // CameraController with ImageAnalysis throttled to 6-8 analyses/sec (~140ms)
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY

            val qrScannerOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val barcodeScanner = BarcodeScanning.getClient(qrScannerOptions)

            var lastAnalysisTime = 0L
            val minIntervalMs = 140L // ~7 analyses per second

            val analyzer = MlKitAnalyzer(
                listOf(barcodeScanner),
                CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(context)
            ) { result ->
                val now = SystemClock.uptimeMillis()
                if (now - lastAnalysisTime < minIntervalMs) {
                    return@MlKitAnalyzer
                }
                lastAnalysisTime = now

                val barcodes = result?.getValue(barcodeScanner)?.mapNotNull { barcode ->
                    val raw = barcode.rawValue ?: barcode.displayValue
                    if (raw != null) {
                        BarcodeItem(rawValue = raw, boundingBox = barcode.boundingBox)
                    } else null
                } ?: emptyList()

                currentLiveIndicator = tracker.onAnalysisResult(barcodes, System.currentTimeMillis())
            }

            setImageAnalysisAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
        }
    }

    DisposableEffect(lifecycleOwner) {
        cameraController.bindToLifecycle(lifecycleOwner)
        onDispose {
            cameraController.clearImageAnalysisAnalyzer()
            cameraController.unbind()
        }
    }

    // Instant Done handler: calculates summary, finishes session, navigates back immediately
    val onDoneClicked: () -> Unit = {
        val total = sessionCount
        val summary = if (total > 0) {
            val codesFound = sessionCoupons.count { it.code != null }
            val needReview = sessionCoupons.count {
                it.status == CouponStatus.NEEDS_REVIEW && it.extractionState == ExtractionState.DONE
            }
            val reading = sessionCoupons.count { it.extractionState == ExtractionState.PENDING }

            buildString {
                append("$total saved")
                if (codesFound > 0) append(", $codesFound codes found")
                if (needReview > 0) append(", $needReview need review")
                if (reading > 0) append(", $reading reading...")
            }
        } else null

        viewModel.finishSession(summary)
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("capture_screen")
    ) {
        // CameraX Surface Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller = cameraController
                    post {
                        if (width > 0) previewWidthPx = width.toFloat()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Live Viewfinder Overlay (Replacing static guide frame)
        LiveViewfinderOverlay(
            indicator = currentLiveIndicator,
            modifier = Modifier.fillMaxSize()
        )

        // Shutter flash animation (instant visual feedback on tap)
        AnimatedVisibility(
            visible = showShutterFlash,
            enter = fadeIn(tween(25)),
            exit = fadeOut(tween(90)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.55f))
            )
        }

        // Top Bar: Tidy row with Close, Session counter pill, Torch toggle.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close button (48dp touch target)
            IconButton(
                onClick = onDoneClicked,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .testTag("close_camera_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close camera",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Running Session Counter: quiet paper pill, increments immediately on tap
            Surface(
                shape = PillShape,
                color = Color.Black.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(PrimaryClay)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (sessionCount == 1) "1 photo taken" else "$sessionCount photos taken",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Torch Toggle (48dp touch target)
            IconButton(
                onClick = {
                    val target = !isTorchEnabled
                    cameraController.enableTorch(target)
                    isTorchEnabled = target
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .testTag("toggle_torch_button")
            ) {
                Icon(
                    imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Toggle flash",
                    tint = if (isTorchEnabled) Color(0xFFFFCC00) else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Bottom-anchored quiet paper snackbar: Never covers camera center
        AnimatedVisibility(
            visible = captureFeedback != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 148.dp, start = 24.dp, end = 24.dp)
        ) {
            captureFeedback?.let { feedback ->
                Surface(
                    shape = FieldShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconTint = when {
                            feedback.isSuccess && !feedback.isWarning -> StatusRedeemedText
                            feedback.isWarning -> StatusNeedsReviewText
                            else -> BrickError
                        }
                        Icon(
                            imageVector = if (feedback.isSuccess && !feedback.isWarning) Icons.Default.Check else Icons.Default.Info,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = feedback.message,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Bottom Controls Container with session strip
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
        ) {
            // Optional Session Strip (last 5 photos with live status badges)
            if (sessionCoupons.isNotEmpty()) {
                val recentCoupons = remember(sessionCoupons) {
                    sessionCoupons.takeLast(5)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recentCoupons.forEach { coupon ->
                        SessionStripThumbnail(coupon = coupon)
                    }
                }
            }

            // Bottom Actions Row: Gallery Import shortcut, Ivory Shutter with Clay Center, Readable Done button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Import / Last Photo Slot with live extraction badge (48dp target)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(FieldShape)
                        .clickable { onLaunchImport() }
                        .padding(4.dp)
                        .testTag("gallery_import_shortcut")
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(FieldShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), FieldShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val imagePath = lastCapturedCoupon?.imagePath ?: lastCapturedImagePath
                        if (imagePath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(File(imagePath))
                                    .crossfade(true)
                                    .size(100, 100)
                                    .build(),
                                contentDescription = "Last captured photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Tiny badge for the last photo
                            lastCapturedCoupon?.let { coupon ->
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.75f))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        coupon.extractionState == ExtractionState.PENDING -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(10.dp),
                                                strokeWidth = 1.5.dp,
                                                color = Color.White
                                            )
                                        }
                                        coupon.code != null -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color(0xFF5A7A4A),
                                                    modifier = Modifier.size(10.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = coupon.code.take(4),
                                                    color = Color.White,
                                                    fontSize = 8.sp,
                                                    fontFamily = CodeFontFamily,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        else -> {
                                            Text(
                                                text = "?",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontFamily = UiFontFamily,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Import from gallery",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Import",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Shutter Button: Instant non-blocking tap, NO SPINNER
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFBF9F4)) // Ivory outer ring
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1F1D1A)) // Thin dark spacer
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(PrimaryClay) // Clay center
                        .clickable {
                            viewModel.capturePhoto(
                                context = context,
                                cameraController = cameraController,
                                onTapAccepted = {
                                    showShutterFlash = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )
                        }
                        .testTag("shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    // Shutter button center core (instant response, never blocked)
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(PrimaryClay)
                    )
                }

                // Done Button: Always instant, shows session summary
                Button(
                    onClick = onDoneClicked,
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFBF9F4),
                        contentColor = Color(0xFF1F1D1A)
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("done_batch_button")
                ) {
                    Text(
                        text = "Done",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // Batch progress indicator modal during gallery import
    batchProgress?.let { progress ->
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = progress.title,
                        fontFamily = TitleFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) progress.current.toFloat() / progress.total.toFloat() else 0f },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${progress.current} of ${progress.total}",
                        fontSize = 13.sp,
                        fontFamily = UiFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Gallery Delete Offer Dialog
    if (showDeleteGalleryDialog && pendingDeleteUris.isNotEmpty()) {
        val photoCount = pendingDeleteUris.size
        val countText = if (photoCount == 1) "1 photo has" else "$photoCount photos have"
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteGalleryDialog() },
            title = {
                Text(
                    text = "Delete from phone gallery?",
                    fontFamily = TitleFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(
                    text = "$countText been saved privately in CouponVault. Delete the originals from your gallery to free up space?",
                    fontFamily = UiFontFamily,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmDelete,
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrickError,
                        contentColor = Color.White
                    )
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
                    onClick = { viewModel.dismissDeleteGalleryDialog() },
                    shape = ButtonShape
                ) {
                    Text(
                        text = "Keep",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = CardShape
        )
    }
}

/**
 * Session strip item displaying a small thumbnail of a recently captured coupon
 * along with its live background extraction state badge.
 */
@Composable
private fun SessionStripThumbnail(coupon: Coupon) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(coupon.imagePath))
                .crossfade(true)
                .size(72, 72)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Extraction status badge overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(1.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.75f))
                .size(12.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                coupon.extractionState == ExtractionState.PENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(8.dp),
                        strokeWidth = 1.2.dp,
                        color = Color.White
                    )
                }
                coupon.code != null -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF5A7A4A),
                        modifier = Modifier.size(8.dp)
                    )
                }
                else -> {
                    Text(
                        text = "?",
                        color = Color.White,
                        fontSize = 7.sp,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Live Viewfinder Overlay that draws dynamic corner brackets and hint pills
 * based on ML Kit real-time detection.
 */
@Composable
private fun LiveViewfinderOverlay(
    indicator: LiveQrIndicator,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Draw brackets / frame on Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (indicator) {
                is LiveQrIndicator.Searching -> {
                    // Thin neutral guide frame centered
                    val frameSize = 260.dp.toPx()
                    val left = (size.width - frameSize) / 2f
                    val top = (size.height - frameSize) / 2f
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.4f),
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(frameSize, frameSize),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                }
                is LiveQrIndicator.MoveCloser -> {
                    drawCornerBrackets(
                        box = indicator.box,
                        color = Color(0xFFD97706), // Amber
                        strokeWidth = 3.dp.toPx(),
                        bracketLength = 18.dp.toPx()
                    )
                }
                is LiveQrIndicator.NotCoupon -> {
                    drawCornerBrackets(
                        box = indicator.box,
                        color = Color(0xFFD97706), // Amber
                        strokeWidth = 3.dp.toPx(),
                        bracketLength = 18.dp.toPx()
                    )
                }
                is LiveQrIndicator.Scannable -> {
                    drawCornerBrackets(
                        box = indicator.box,
                        color = Color(0xFF5A7A4A), // Sage green
                        strokeWidth = 3.5.dp.toPx(),
                        bracketLength = 20.dp.toPx()
                    )
                }
            }
        }

        // Live Hint Pill Overlay
        when (indicator) {
            is LiveQrIndicator.Searching -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = PillShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier.padding(top = 290.dp)
                    ) {
                        Text(
                            text = "Point at a coupon QR",
                            color = Color.White,
                            fontFamily = UiFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            is LiveQrIndicator.MoveCloser -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = PillShape,
                        color = Color(0xFFD97706),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "Move closer",
                            color = Color.White,
                            fontFamily = UiFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            is LiveQrIndicator.NotCoupon -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = PillShape,
                        color = Color(0xFFD97706),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "QR found, but not a coupon",
                            color = Color.White,
                            fontFamily = UiFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            is LiveQrIndicator.Scannable -> {
                val shortenedCode = if (indicator.code.length > 12) {
                    "${indicator.code.take(6)}...${indicator.code.takeLast(4)}"
                } else {
                    indicator.code
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = PillShape,
                        color = Color(0xFF3F5A2E), // Sage green
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = shortenedCode,
                                color = Color.White,
                                fontFamily = CodeFontFamily,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws crisp corner brackets snapped around a bounding box.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerBrackets(
    box: RectF,
    color: Color,
    strokeWidth: Float,
    bracketLength: Float
) {
    // Inflate slightly for breathing room
    val padding = 10f
    val left = box.left - padding
    val top = box.top - padding
    val right = box.right + padding
    val bottom = box.bottom + padding

    val l = bracketLength.coerceAtMost((right - left) / 2.5f)
    val cap = StrokeCap.Round

    // Top-Left
    drawLine(color, Offset(left, top), Offset(left + l, top), strokeWidth, cap)
    drawLine(color, Offset(left, top), Offset(left, top + l), strokeWidth, cap)

    // Top-Right
    drawLine(color, Offset(right, top), Offset(right - l, top), strokeWidth, cap)
    drawLine(color, Offset(right, top), Offset(right, top + l), strokeWidth, cap)

    // Bottom-Left
    drawLine(color, Offset(left, bottom), Offset(left + l, bottom), strokeWidth, cap)
    drawLine(color, Offset(left, bottom), Offset(left, bottom - l), strokeWidth, cap)

    // Bottom-Right
    drawLine(color, Offset(right, bottom), Offset(right - l, bottom), strokeWidth, cap)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - l), strokeWidth, cap)
}

@Composable
fun CameraPermissionRationale(
    onRequestPermission: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
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
                    imageVector = Icons.Default.Camera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Camera Access",
                fontFamily = TitleFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "CouponVault saves coupon photos privately on your device. The camera stays open so you can capture coupons quickly.",
                fontFamily = UiFontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermission,
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("grant_permission_button")
            ) {
                Text(
                    text = "Allow camera",
                    fontFamily = UiFontFamily,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onClose,
                shape = ButtonShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Back to coupons",
                    fontFamily = UiFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@ComposePreview(name = "Capture Rationale - Light 360dp", widthDp = 360, fontScale = 1.3f)
@Composable
private fun CaptureRationalePreviewLight() {
    CouponVaultTheme(darkTheme = false) {
        CameraPermissionRationale(onRequestPermission = {}, onClose = {})
    }
}

@ComposePreview(name = "Capture Rationale - Dark 360dp", widthDp = 360, fontScale = 1.3f)
@Composable
private fun CaptureRationalePreviewDark() {
    CouponVaultTheme(darkTheme = true) {
        CameraPermissionRationale(onRequestPermission = {}, onClose = {})
    }
}
