package com.example.couponvault.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.theme.BrickError
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.CouponVaultTheme
import com.example.couponvault.ui.theme.FieldShape
import com.example.couponvault.ui.theme.PillShape
import com.example.couponvault.ui.theme.PrimaryClay
import com.example.couponvault.ui.theme.StatusNeedsReviewText
import com.example.couponvault.ui.theme.StatusRedeemedText
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily
import kotlinx.coroutines.delay
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

    // MediaStore gallery delete launcher (Android 11+) declared at top level of screen composable
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

    val sessionCount by viewModel.sessionPhotoCount.collectAsStateWithLifecycle()
    val isCapturing by viewModel.isCapturing.collectAsStateWithLifecycle()
    val captureFeedback by viewModel.captureFeedback.collectAsStateWithLifecycle()
    val lastCapturedImagePath by viewModel.lastCapturedImagePath.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()

    val showDeleteGalleryDialog by viewModel.showDeleteGalleryDialog.collectAsStateWithLifecycle()
    val pendingDeleteUris by viewModel.pendingDeleteUris.collectAsStateWithLifecycle()

    var isTorchEnabled by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    // Auto-dismiss the feedback banner after 2.8 seconds
    LaunchedEffect(captureFeedback) {
        if (captureFeedback != null) {
            delay(2800)
            viewModel.dismissFeedback()
        }
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
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                        activeCamera = camera
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay reticle guide: Hairline paper frame with generous breathing room
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(16.dp)
                    )
            )
        }

        // Top Bar: Tidy row with Close, Session counter pill, Torch toggle.
        // Generous spacing between elements so pills NEVER touch each other.
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
                onClick = onClose,
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

            // Running Session Counter: quiet paper pill
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
                    activeCamera?.cameraControl?.enableTorch(target)
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

        // Bottom-anchored quiet paper snackbar: Never covers the center of the camera preview
        AnimatedVisibility(
            visible = captureFeedback != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 110.dp, start = 24.dp, end = 24.dp)
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

        // Bottom Controls: Single Gallery Import shortcut, Ivory Shutter with Clay Center, Readable Done button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Single Gallery Import Shortcut Button (48dp touch target)
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
                            .size(48.dp)
                            .clip(FieldShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), FieldShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (lastCapturedImagePath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(File(lastCapturedImagePath!!))
                                    .crossfade(true)
                                    .size(96, 96)
                                    .build(),
                                contentDescription = "Last captured photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Import from gallery",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Import",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Shutter Button: Ivory ring with Clay center
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFBF9F4)) // Ivory outer ring
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1F1D1A)) // Thin separator ring
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(PrimaryClay) // Clay center
                        .clickable(enabled = !isCapturing) {
                            viewModel.capturePhoto(context, imageCapture)
                        }
                        .testTag("shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                }

                // Readable "Done" Button: High-contrast Ivory paper button with dark text
                Button(
                    onClick = onClose,
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

    // Gallery Delete Offer Dialog (Defect #9):
    // Standard AlertDialog: serif title, 2-3 line body, right-aligned row with [Keep] as text button and [Delete] as filled brick-tone button.
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
