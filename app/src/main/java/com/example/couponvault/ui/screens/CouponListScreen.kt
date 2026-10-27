package com.example.couponvault.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.components.StatusBadge
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.CodeFontFamily
import com.example.couponvault.ui.theme.CouponVaultTheme
import com.example.couponvault.ui.theme.PillShape
import com.example.couponvault.ui.theme.StatusReviewContainerDark
import com.example.couponvault.ui.theme.StatusReviewContainerLight
import com.example.couponvault.ui.theme.StatusReviewTextDark
import com.example.couponvault.ui.theme.StatusReviewTextLight
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily
import com.example.couponvault.util.StorageHelper
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CouponListScreen(
    viewModel: CouponViewModel,
    onNavigateToCapture: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToReviewQueue: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val coupons by viewModel.coupons.collectAsStateWithLifecycle()
    val unscannedCount by viewModel.unscannedCount.collectAsStateWithLifecycle()
    val needsReviewCount by viewModel.needsReviewCount.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val highlightedId by viewModel.highlightedCouponId.collectAsStateWithLifecycle()
    val captureFeedback by viewModel.captureFeedback.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()

    val crashReport by viewModel.crashReport.collectAsStateWithLifecycle()
    val showDeleteGalleryDialog by viewModel.showDeleteGalleryDialog.collectAsStateWithLifecycle()
    val pendingDeleteUris by viewModel.pendingDeleteUris.collectAsStateWithLifecycle()

    val selectedCouponIds by viewModel.selectedCouponIds.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()

    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val isOverlayEnabled by viewModel.isOverlayEnabled.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val autoMarkRedeemed by viewModel.autoMarkRedeemed.collectAsStateWithLifecycle()

    // Document Picker launcher for gallery import (needs no runtime storage permissions)
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

    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingEnableOverlay by rememberSaveable { mutableStateOf(false) }

    // Notification permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (Settings.canDrawOverlays(context)) {
            viewModel.setOverlayEnabled(true, context)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (pendingEnableOverlay) {
                    if (Settings.canDrawOverlays(context)) {
                        pendingEnableOverlay = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.setOverlayEnabled(true, context)
                    } else {
                        pendingEnableOverlay = false
                        viewModel.setOverlayEnabled(false, context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val onToggleOverlay: (Boolean) -> Unit = { shouldEnable ->
        if (shouldEnable) {
            if (!Settings.canDrawOverlays(context)) {
                pendingEnableOverlay = true
                showOverlayPermissionDialog = true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.setOverlayEnabled(true, context)
            }
        } else {
            pendingEnableOverlay = false
            viewModel.setOverlayEnabled(false, context)
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    // SAF Folder Picker Launcher for export all originals
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.exportAllOriginals(context, treeUri) { success, msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            }
        }
    }

    LaunchedEffect(captureFeedback) {
        captureFeedback?.let { feedback ->
            snackbarHostState.showSnackbar(feedback.message)
            viewModel.dismissFeedback()
        }
    }

    val listState = rememberLazyListState()
    val isFabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("coupon_list_screen"),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                // Bulk Selection Mode TopAppBar
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedCouponIds.size} selected",
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearCouponSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllCoupons() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { viewModel.bulkUpdateStatus(CouponStatus.REDEEMED) }) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Mark Redeemed")
                        }
                        IconButton(onClick = { viewModel.bulkUpdateStatus(CouponStatus.UNSCANNED) }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Mark Unscanned")
                        }
                        IconButton(onClick = { viewModel.bulkUpdateStatus(CouponStatus.FAILED) }) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = "Mark Failed")
                        }
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            } else {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (needsReviewCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("Review queue ($needsReviewCount)", fontFamily = UiFontFamily) },
                                    leadingIcon = { Icon(Icons.Default.HelpOutline, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToReviewQueue()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Import photos", fontFamily = UiFontFamily) },
                                leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("image/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (autoMarkRedeemed) "Auto-mark redeemed: On" else "Auto-mark redeemed: Off",
                                        fontFamily = UiFontFamily
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (autoMarkRedeemed) Icons.Default.CheckCircle else Icons.Default.Close,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    viewModel.setAutoMarkRedeemed(!autoMarkRedeemed)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export all originals (folder)", fontFamily = UiFontFamily) },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    openDocumentTreeLauncher.launch(null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share codes as CSV", fontFamily = UiFontFamily) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        val intent = viewModel.getShareCsvIntent(context)
                                        context.startActivity(Intent.createChooser(intent, "Share codes CSV"))
                                    }
                                }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = onNavigateToCapture,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = PillShape,
                    modifier = Modifier.testTag("fab_capture")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = if (isFabExpanded) 18.dp else 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Capture coupon"
                        )
                        AnimatedVisibility(visible = isFabExpanded) {
                            Row {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Capture",
                                    fontFamily = UiFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomListPadding = 56.dp + 24.dp + navBarBottom

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .testTag("coupon_list"),
            contentPadding = PaddingValues(bottom = bottomListPadding)
        ) {
            // Header Section placed inside LazyColumn so it scrolls away naturally
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    // Screen title & subtitle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CouponVault",
                            fontFamily = TitleFontFamily,
                            fontWeight = FontWeight.Normal,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(PillShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = "Private storage",
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Private",
                                    fontFamily = UiFontFamily,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = "$unscannedCount unscanned • ${coupons.size} total",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = UiFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Simplified Floating Paste Button Toggle Card
                    val isOverlayActive = isOverlayEnabled && isServiceRunning
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("floating_paste_card"),
                        shape = CardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = "Floating paste button",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = UiFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (queueState.isAllDone) {
                                            "All coupons redeemed"
                                        } else {
                                            "${queueState.remainingCount} in queue • Tap to copy into Havells"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = UiFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = isOverlayActive,
                                    onCheckedChange = { onToggleOverlay(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier.testTag("floating_paste_switch")
                                )
                            }

                            // Current code stacked on its own line beneath in monospace
                            val currentCode = queueState.currentCode
                            if (isOverlayActive || currentCode != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Current: ",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = UiFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = currentCode ?: "None copied yet",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = CodeFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (currentCode != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Compact single-row review banner
                    if (needsReviewCount > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val isDark = isSystemInDarkTheme()
                        val bannerContainer = if (isDark) StatusReviewContainerDark else StatusReviewContainerLight
                        val bannerText = if (isDark) StatusReviewTextDark else StatusReviewTextLight

                        Surface(
                            shape = CardShape,
                            color = bannerContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToReviewQueue() }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HelpOutline,
                                    contentDescription = null,
                                    tint = bannerText,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "$needsReviewCount need review",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = UiFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = bannerText,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(
                                    onClick = { onNavigateToReviewQueue() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Review",
                                        fontFamily = UiFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        color = bannerText,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Sticky Header: Filter chips only
            stickyHeader {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val filters = listOf<Pair<CouponStatus?, String>>(
                            null to "All (${coupons.size})",
                            CouponStatus.UNSCANNED to "Unscanned ($unscannedCount)",
                            CouponStatus.REDEEMED to "Redeemed",
                            CouponStatus.NEEDS_REVIEW to "Needs review ($needsReviewCount)",
                            CouponStatus.FAILED to "Failed"
                        )
                        filters.forEach { (status, label) ->
                            val isSelected = selectedFilter == status
                            val testTag = when (status) {
                                null -> "filter_all"
                                CouponStatus.UNSCANNED -> "filter_unscanned"
                                CouponStatus.REDEEMED -> "filter_redeemed"
                                CouponStatus.NEEDS_REVIEW -> "filter_needs_review"
                                CouponStatus.FAILED -> "filter_failed"
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setFilter(status) },
                                label = {
                                    Text(
                                        text = label,
                                        fontFamily = UiFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                shape = PillShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier.testTag(testTag)
                            )
                        }
                    }
                }
            }

            // Content List / Empty State
            if (coupons.isEmpty()) {
                item {
                    RefinedEmptyState(
                        filter = selectedFilter,
                        onCaptureClick = onNavigateToCapture
                    )
                }
            } else {
                items(
                    items = coupons,
                    key = { it.id }
                ) { coupon ->
                    val isHighlighted = coupon.id == highlightedId
                    val isSelected = selectedCouponIds.contains(coupon.id)

                    CouponItemCard(
                        coupon = coupon,
                        isHighlighted = isHighlighted,
                        isSelected = isSelected,
                        isSelectionMode = isSelectionMode,
                        onClick = {
                            if (isSelectionMode) {
                                viewModel.toggleCouponSelection(coupon.id)
                            } else {
                                viewModel.clearHighlight()
                                onNavigateToDetail(coupon.id)
                            }
                        },
                        onLongClick = {
                            viewModel.toggleCouponSelection(coupon.id)
                        },
                        onCopyCode = { code ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Coupon Code", code))
                            Toast.makeText(context, "Copied: $code", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    // Progress modal during gallery import
    batchProgress?.let { progress ->
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = progress.title,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
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
                        fontSize = 12.sp,
                        fontFamily = UiFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Standard AlertDialog for Bulk Delete
    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = {
                Text(
                    text = "Delete ${selectedCouponIds.size} coupons?",
                    fontFamily = TitleFontFamily,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "These coupon records and their private photo copies will be permanently deleted.",
                    fontFamily = UiFontFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBulkDeleteDialog = false
                        viewModel.bulkDelete()
                    },
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Delete", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text("Cancel", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Overlay Permission Explanation Dialog
    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showOverlayPermissionDialog = false
                pendingEnableOverlay = false
            },
            title = {
                Text(
                    text = "Display over other apps",
                    fontFamily = TitleFontFamily,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "To paste coupon codes into Havells or other apps one by one, CouponVault needs permission to display a floating button over other apps.",
                    fontFamily = UiFontFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("open_overlay_settings_button")
                ) {
                    Text("Open Settings", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOverlayPermissionDialog = false
                        pendingEnableOverlay = false
                    }
                ) {
                    Text("Cancel", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Crash Report Dialog
    if (crashReport != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCrashReport() },
            title = {
                Text(
                    text = "The app crashed last time",
                    fontFamily = TitleFontFamily,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = crashReport ?: "",
                        fontSize = 11.sp,
                        fontFamily = CodeFontFamily,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "CouponVault Crash Report")
                            putExtra(Intent.EXTRA_TEXT, crashReport)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Crash Report"))
                        viewModel.dismissCrashReport()
                    },
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Share", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Crash Report", crashReport))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Copy", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    TextButton(onClick = { viewModel.dismissCrashReport() }) {
                        Text("Dismiss", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Standard AlertDialog for "Delete from Phone Gallery?"
    if (showDeleteGalleryDialog && pendingDeleteUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteGalleryDialog() },
            title = {
                Text(
                    text = "Delete from gallery?",
                    fontFamily = TitleFontFamily,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                val photoCount = pendingDeleteUris.size
                val countText = if (photoCount == 1) "1 photo has" else "$photoCount photos have"
                Text(
                    text = "$countText been safely saved inside CouponVault. Would you like to delete the originals from your phone gallery to keep your photos tidy?",
                    fontFamily = UiFontFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmDelete,
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Delete", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteGalleryDialog() }) {
                    Text("Keep", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CouponItemCard(
    coupon: Coupon,
    isHighlighted: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isHighlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("coupon_card_${coupon.id}"),
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.surfaceVariant
                isHighlighted -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected || isHighlighted) 1.5.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Thumbnail on paper mat
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(coupon.imagePath))
                        .crossfade(true)
                        .size(144, 144)
                        .scale(Scale.FIT)
                        .build(),
                    contentDescription = "Coupon Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(
                        status = coupon.status,
                        extractionState = coupon.extractionState
                    )

                    if (!coupon.code.isNullOrBlank() && !isSelectionMode) {
                        IconButton(
                            onClick = { onCopyCode(coupon.code) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("copy_button_${coupon.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy code",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Coupon code strictly in monospace
                if (!coupon.code.isNullOrBlank()) {
                    Text(
                        text = coupon.code,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = CodeFontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val fallbackText = when {
                        coupon.extractionState == ExtractionState.PENDING -> "Reading photo..."
                        coupon.status == CouponStatus.NEEDS_REVIEW -> "Tap to review photo & code"
                        else -> "No code found"
                    }
                    val fallbackColor = if (coupon.extractionState == ExtractionState.PENDING) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = fallbackText,
                        fontSize = 13.sp,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Normal,
                        color = fallbackColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = StorageHelper.formatDateTime(coupon.createdAt),
                        fontSize = 11.sp,
                        fontFamily = UiFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = coupon.source.name,
                        fontSize = 11.sp,
                        fontFamily = UiFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun RefinedEmptyState(
    filter: CouponStatus?,
    onCaptureClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineColor = MaterialTheme.colorScheme.outline
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Refined line-art drawing of paper coupon
        Canvas(modifier = Modifier.size(80.dp)) {
            val strokeWidth = 1.5.dp.toPx()
            val w = size.width
            val h = size.height

            val cornerFold = 16.dp.toPx()
            val path = Path().apply {
                moveTo(10.dp.toPx(), 10.dp.toPx())
                lineTo(w - 10.dp.toPx() - cornerFold, 10.dp.toPx())
                lineTo(w - 10.dp.toPx(), 10.dp.toPx() + cornerFold)
                lineTo(w - 10.dp.toPx(), h - 10.dp.toPx())
                lineTo(10.dp.toPx(), h - 10.dp.toPx())
                close()
            }
            drawPath(path, color = outlineColor, style = Stroke(width = strokeWidth))

            val foldPath = Path().apply {
                moveTo(w - 10.dp.toPx() - cornerFold, 10.dp.toPx())
                lineTo(w - 10.dp.toPx() - cornerFold, 10.dp.toPx() + cornerFold)
                lineTo(w - 10.dp.toPx(), 10.dp.toPx() + cornerFold)
            }
            drawPath(foldPath, color = outlineColor, style = Stroke(width = strokeWidth))

            drawLine(
                color = outlineColor,
                start = Offset(20.dp.toPx(), h * 0.58f),
                end = Offset(w - 20.dp.toPx(), h * 0.58f),
                strokeWidth = strokeWidth
            )

            // Stylized QR corner in clay accent
            val markSize = 9.dp.toPx()
            val markX = 20.dp.toPx()
            val markY = 22.dp.toPx()
            val qrCorner = Path().apply {
                moveTo(markX, markY + markSize)
                lineTo(markX, markY)
                lineTo(markX + markSize, markY)
            }
            drawPath(qrCorner, color = primaryColor, style = Stroke(width = strokeWidth * 1.5f))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = if (filter == null) "No coupons yet" else "No ${filter.name.lowercase().replace('_', ' ')} coupons",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = TitleFontFamily,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (filter == null) {
                "Capture coupons or import photos to get started."
            } else {
                "No coupons match this filter."
            },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = UiFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onCaptureClick,
            shape = ButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Capture coupon",
                fontFamily = UiFontFamily,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// --- Previews per Part 5 (360dp width, fontScale 1.3f, light and dark) ---

@Preview(name = "Home Light 360dp Empty", widthDp = 360, fontScale = 1.3f, showBackground = true)
@Composable
private fun PreviewHomeLightEmpty() {
    CouponVaultTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RefinedEmptyState(filter = null, onCaptureClick = {})
        }
    }
}

@Preview(name = "Home Dark 360dp Empty", widthDp = 360, fontScale = 1.3f, showBackground = true)
@Composable
private fun PreviewHomeDarkEmpty() {
    CouponVaultTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RefinedEmptyState(filter = null, onCaptureClick = {})
        }
    }
}

@Preview(name = "Coupon Card 24-char Code Light", widthDp = 360, fontScale = 1.3f, showBackground = true)
@Composable
private fun PreviewCouponCardLight() {
    CouponVaultTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            CouponItemCard(
                coupon = Coupon(
                    id = 1L,
                    code = "ABCDEFGH1234567890IJKLMN",
                    imagePath = "/dummy/path.jpg",
                    status = CouponStatus.UNSCANNED,
                    source = CouponSource.QR,
                    createdAt = System.currentTimeMillis()
                ),
                isHighlighted = false,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongClick = {},
                onCopyCode = {},
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

@Preview(name = "Coupon Card 24-char Code Dark", widthDp = 360, fontScale = 1.3f, showBackground = true)
@Composable
private fun PreviewCouponCardDark() {
    CouponVaultTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            CouponItemCard(
                coupon = Coupon(
                    id = 1L,
                    code = "ABCDEFGH1234567890IJKLMN",
                    imagePath = "/dummy/path.jpg",
                    status = CouponStatus.REDEEMED,
                    source = CouponSource.QR,
                    createdAt = System.currentTimeMillis()
                ),
                isHighlighted = false,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongClick = {},
                onCopyCode = {},
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}
