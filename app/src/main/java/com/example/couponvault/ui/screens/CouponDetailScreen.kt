package com.example.couponvault.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.components.StatusBadge
import com.example.couponvault.ui.theme.BrickError
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.CodeFontFamily
import com.example.couponvault.ui.theme.CouponVaultTheme
import com.example.couponvault.ui.theme.FieldShape
import com.example.couponvault.ui.theme.PillShape
import com.example.couponvault.ui.theme.PrimaryClay
import com.example.couponvault.ui.theme.StatusFailedBg
import com.example.couponvault.ui.theme.StatusFailedText
import com.example.couponvault.ui.theme.StatusNeedsReviewBg
import com.example.couponvault.ui.theme.StatusNeedsReviewText
import com.example.couponvault.ui.theme.StatusRedeemedBg
import com.example.couponvault.ui.theme.StatusRedeemedText
import com.example.couponvault.ui.theme.StatusUnscannedBg
import com.example.couponvault.ui.theme.StatusUnscannedText
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily
import com.example.couponvault.util.StorageHelper
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouponDetailScreen(
    couponId: Long,
    viewModel: CouponViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val allCoupons by viewModel.coupons.collectAsStateWithLifecycle()
    val coupon = allCoupons.find { it.id == couponId }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isEditingCode by remember { mutableStateOf(false) }
    var editedCodeInput by remember(coupon?.code) { mutableStateOf(coupon?.code ?: "") }
    var editErrorMessage by remember { mutableStateOf<String?>(null) }

    // Pinch-to-zoom transformation states
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val downloadingSyncIds by viewModel.downloadingSyncIds.collectAsStateWithLifecycle()
    val uploadingSyncIds by viewModel.uploadingSyncIds.collectAsStateWithLifecycle()

    val isDownloading = coupon != null && downloadingSyncIds.contains(coupon.syncId)
    val isUploading = coupon != null && uploadingSyncIds.contains(coupon.syncId)

    // Trigger lazy download if coupon opened and local photo is missing
    LaunchedEffect(coupon?.syncId, coupon?.remoteImagePath) {
        if (coupon != null && !coupon.remoteImagePath.isNullOrBlank()) {
            val file = if (coupon.imagePath.isNotBlank()) File(coupon.imagePath) else null
            if (file == null || !file.exists()) {
                viewModel.enqueueLazyPhotoDownload(coupon)
            }
        }
    }

    if (coupon == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Coupon",
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
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Coupon not found.",
                    fontFamily = UiFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("coupon_detail_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = coupon.code ?: "Coupon",
                            fontFamily = if (coupon.code != null) CodeFontFamily else TitleFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Private storage",
                            fontFamily = UiFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("detail_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val intent = viewModel.getShareIntent(context, coupon)
                            context.startActivity(Intent.createChooser(intent, "Share original photo"))
                        },
                        modifier = Modifier.testTag("share_original_button")
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share photo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
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
            // Photo on paper mat with hairline frame
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                val imageFile = if (coupon.imagePath.isNotBlank()) File(coupon.imagePath) else null
                val hasLocalPhoto = imageFile != null && imageFile.exists()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.05f))
                ) {
                    if (hasLocalPhoto) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageFile)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Coupon photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
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

                        // Zoom reset indicator
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
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Downloading...",
                                    fontFamily = UiFontFamily,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else if (!coupon.remoteImagePath.isNullOrBlank()) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudQueue,
                                    contentDescription = "Photo not on device",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Not downloaded yet",
                                    fontFamily = UiFontFamily,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Photo stored in your cloud vault.",
                                    fontFamily = UiFontFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.downloadPhoto(coupon) },
                                    shape = ButtonShape,
                                    modifier = Modifier.testTag("download_photo_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FileDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Download",
                                        fontFamily = UiFontFamily,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.CloudQueue,
                                    contentDescription = "No photo",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No photo attached",
                                    fontFamily = UiFontFamily,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Floating Photo Status Markers & Reduced Copy Tag
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // "Reduced copy" marker
                        if (coupon.imageVariant == "reduced") {
                            Surface(
                                shape = PillShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.testTag("reduced_copy_badge")
                            ) {
                                Text(
                                    text = "Reduced copy",
                                    fontFamily = UiFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // State markers: "In cloud", "Uploading", "Downloading", "Not downloaded yet"
                        if (hasLocalPhoto) {
                            if (!coupon.remoteImagePath.isNullOrBlank()) {
                                Surface(
                                    shape = PillShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.testTag("in_cloud_badge")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "In cloud",
                                            fontFamily = UiFontFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            } else if (isUploading) {
                                Surface(
                                    shape = PillShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.testTag("uploading_badge")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(11.dp),
                                            strokeWidth = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Uploading",
                                            fontFamily = UiFontFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else if (!coupon.remoteImagePath.isNullOrBlank()) {
                            if (isDownloading) {
                                Surface(
                                    shape = PillShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.testTag("downloading_badge")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(11.dp),
                                            strokeWidth = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Downloading",
                                            fontFamily = UiFontFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = PillShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.testTag("not_downloaded_badge")
                                ) {
                                    Text(
                                        text = "Not downloaded yet",
                                        fontFamily = UiFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Coupon Code Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadge(status = coupon.status)

                        TextButton(
                            onClick = {
                                isEditingCode = !isEditingCode
                                editedCodeInput = coupon.code ?: ""
                                editErrorMessage = null
                            }
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isEditingCode) "Cancel" else "Edit code",
                                fontFamily = UiFontFamily,
                                color = MaterialTheme.colorScheme.primary,
                                softWrap = false
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isEditingCode) {
                        OutlinedTextField(
                            value = editedCodeInput,
                            onValueChange = {
                                editedCodeInput = it.uppercase()
                                editErrorMessage = null
                            },
                            label = { Text("Coupon code", fontFamily = UiFontFamily) },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = CodeFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            singleLine = true,
                            isError = editErrorMessage != null,
                            supportingText = {
                                if (editErrorMessage != null) {
                                    Text(
                                        editErrorMessage!!,
                                        fontFamily = UiFontFamily,
                                        color = BrickError
                                    )
                                } else {
                                    Text(
                                        "Enter the code printed under the QR.",
                                        fontFamily = UiFontFamily
                                    )
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
                                    viewModel.saveManualCode(coupon, editedCodeInput) { success, msg ->
                                        if (success) {
                                            isEditingCode = false
                                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                                        } else {
                                            editErrorMessage = msg
                                        }
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_code_textfield")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                viewModel.saveManualCode(coupon, editedCodeInput) { success, msg ->
                                    if (success) {
                                        isEditingCode = false
                                        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                                    } else {
                                        editErrorMessage = msg
                                    }
                                }
                            },
                            shape = ButtonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Save code",
                                fontFamily = UiFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Text(
                            text = "CODE",
                            fontFamily = UiFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = coupon.code ?: "No code found",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = CodeFontFamily,
                                color = if (coupon.code != null) MaterialTheme.colorScheme.onSurface else BrickError,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (!coupon.code.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Coupon Code", coupon.code))
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Copied: ${coupon.code}")
                                        }
                                    },
                                    shape = ButtonShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier
                                        .height(44.dp)
                                        .testTag("copy_code_detail_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold, softWrap = false)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Calm metadata
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Captured ${StorageHelper.formatDateTime(coupon.createdAt)}",
                            fontFamily = UiFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (coupon.redeemedAt != null) {
                            Text(
                                text = "Redeemed ${StorageHelper.formatDateTime(coupon.redeemedAt)}",
                                fontFamily = UiFontFamily,
                                fontSize = 12.sp,
                                color = StatusRedeemedText
                            )
                        }
                    }
                }
            }

            // Status Selector Card: Segmented status pills
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Status",
                        fontFamily = TitleFontFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Row 1: Unscanned and Redeemed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusOptionPill(
                            title = "Unscanned",
                            isSelected = coupon.status == CouponStatus.UNSCANNED,
                            selectedBg = StatusUnscannedBg,
                            selectedText = StatusUnscannedText,
                            onClick = { viewModel.updateStatus(coupon, CouponStatus.UNSCANNED) },
                            modifier = Modifier.weight(1f)
                        )
                        StatusOptionPill(
                            title = "Redeemed",
                            isSelected = coupon.status == CouponStatus.REDEEMED,
                            selectedBg = StatusRedeemedBg,
                            selectedText = StatusRedeemedText,
                            onClick = { viewModel.updateStatus(coupon, CouponStatus.REDEEMED) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Row 2: Needs review and Failed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusOptionPill(
                            title = "Needs review",
                            isSelected = coupon.status == CouponStatus.NEEDS_REVIEW,
                            selectedBg = StatusNeedsReviewBg,
                            selectedText = StatusNeedsReviewText,
                            onClick = { viewModel.updateStatus(coupon, CouponStatus.NEEDS_REVIEW) },
                            modifier = Modifier.weight(1f)
                        )
                        StatusOptionPill(
                            title = "Failed",
                            isSelected = coupon.status == CouponStatus.FAILED,
                            selectedBg = StatusFailedBg,
                            selectedText = StatusFailedText,
                            onClick = { viewModel.updateStatus(coupon, CouponStatus.FAILED) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Photo Export & Secondary Actions
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Photo actions",
                        fontFamily = TitleFontFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedButton(
                        onClick = {
                            viewModel.saveToGallery(context, coupon) { _, msg ->
                                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        shape = ButtonShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_gallery_button")
                    ) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save original to gallery",
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = viewModel.getShareIntent(context, coupon)
                            context.startActivity(Intent.createChooser(intent, "Share original photo"))
                        },
                        shape = ButtonShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("share_button")
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Share photo",
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Brick-tone quiet Delete action
                    TextButton(
                        onClick = { showDeleteConfirmDialog = true },
                        shape = ButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("delete_coupon_button")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = BrickError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete coupon",
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = BrickError
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Standard AlertDialog for deletion confirmation
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = "Delete coupon?",
                    fontFamily = TitleFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(
                    text = "This coupon will be removed from CouponVault. The photo will also be deleted from storage.",
                    fontFamily = UiFontFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteCoupon(coupon) {
                            onNavigateBack()
                        }
                    },
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
                    onClick = { showDeleteConfirmDialog = false },
                    shape = ButtonShape
                ) {
                    Text(
                        text = "Cancel",
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
private fun StatusOptionPill(
    title: String,
    isSelected: Boolean,
    selectedBg: Color,
    selectedText: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isSelected) selectedBg else MaterialTheme.colorScheme.surface
    val border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    val text = if (isSelected) selectedText else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        shape = PillShape,
        color = bg,
        border = border,
        modifier = modifier.height(38.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
        ) {
            Text(
                text = title,
                fontFamily = UiFontFamily,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = text,
                softWrap = false
            )
        }
    }
}

@Preview(name = "Detail - Light 360dp", widthDp = 360, fontScale = 1.3f)
@Composable
private fun CouponDetailPreviewLight() {
    CouponVaultTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatusBadge(status = CouponStatus.UNSCANNED)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "A51PEWKCWB81MXN294719284",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = CodeFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Detail - Dark 360dp", widthDp = 360, fontScale = 1.3f)
@Composable
private fun CouponDetailPreviewDark() {
    CouponVaultTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatusBadge(status = CouponStatus.NEEDS_REVIEW)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "B99QRXCZLA01",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = CodeFontFamily
                        )
                    }
                }
            }
        }
    }
}
