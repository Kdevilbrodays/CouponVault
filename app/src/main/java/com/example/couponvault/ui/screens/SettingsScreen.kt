package com.example.couponvault.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.couponvault.data.BackupValidationResult
import com.example.couponvault.data.DataBackupManager
import com.example.couponvault.data.RestoreMode
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.components.DeleteRedeemedDialog
import com.example.couponvault.ui.theme.BrickError
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.DarkBrickError
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CouponViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAccount: () -> Unit = {}
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isDark = isSystemInDarkTheme()
    val brickColor = if (isDark) DarkBrickError else BrickError

    // State collections
    val isOverlayEnabled by viewModel.isOverlayEnabled.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val autoMarkRedeemed by viewModel.autoMarkRedeemed.collectAsStateWithLifecycle()
    val redeemedCount by viewModel.redeemedCount.collectAsStateWithLifecycle()
    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
    val captureFeedback by viewModel.captureFeedback.collectAsStateWithLifecycle()

    // Overlay Permission Handlers
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingEnableOverlay by rememberSaveable { mutableStateOf(false) }

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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    // Dialog & Flow States
    var showDeleteAllRedeemedDialog by remember { mutableStateOf(false) }
    var showExportWarningDialog by remember { mutableStateOf(false) }
    var pendingRestoreValidation by remember { mutableStateOf<BackupValidationResult?>(null) }
    var restoreMode by remember { mutableStateOf(RestoreMode.MERGE) }
    var showReplaceWarningDialog by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Launchers
    val importPhotosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.importGalleryPhotos(context, uris)
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.exportAllOriginals(context, treeUri) { _, msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            }
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { destinationUri ->
        if (destinationUri != null) {
            viewModel.exportAppData(context, destinationUri) { _, msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            viewModel.validateBackup(context, sourceUri) { result ->
                result.onSuccess { validationResult ->
                    pendingRestoreValidation = validationResult
                }.onFailure { err ->
                    validationError = err.message ?: "Invalid backup file"
                }
            }
        }
    }

    LaunchedEffect(captureFeedback) {
        captureFeedback?.let { feedback ->
            snackbarHostState.showSnackbar(feedback.message)
            viewModel.dismissFeedback()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontFamily = TitleFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp + navBarBottom),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Rapid entry
            item {
                SettingsSectionHeader(title = "Rapid entry")
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column {
                        // Floating Paste Button Toggle
                        SettingsSwitchRow(
                            title = "Floating paste button",
                            subtitle = "Show screen overlay to quickly copy codes into merchant apps",
                            checked = isOverlayEnabled && isServiceRunning,
                            onCheckedChange = { onToggleOverlay(it) },
                            testTag = "settings_floating_paste_switch"
                        )

                        SettingsDivider()

                        // Auto-mark redeemed
                        SettingsSwitchRow(
                            title = "Auto-mark redeemed",
                            subtitle = "Automatically mark current coupon as redeemed when advancing in queue",
                            checked = autoMarkRedeemed,
                            onCheckedChange = { viewModel.setAutoMarkRedeemed(it) },
                            testTag = "settings_auto_mark_redeemed_switch"
                        )
                    }
                }
            }

            // Section 2: Account & Sync
            item {
                val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
                val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

                SettingsSectionHeader(title = "Account & Sync")
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column {
                        SettingsActionRow(
                            title = "Account & Cloud Sync",
                            subtitle = if (!userEmail.isNullOrBlank()) {
                                "Signed in as $userEmail • ${if (syncStatus.state == com.example.couponvault.sync.SyncState.SYNCING) "Syncing..." else "Ready"}"
                            } else {
                                "Sign in to back up and sync across devices"
                            },
                            icon = if (!userEmail.isNullOrBlank()) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                            onClick = onNavigateToAccount,
                            testTag = "settings_account_row"
                        )
                    }
                }
            }

            // Section 3: Data
            item {
                SettingsSectionHeader(title = "Data")
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column {
                        // Import photos from gallery
                        SettingsActionRow(
                            title = "Import photos",
                            subtitle = "Pick images from gallery to add to review queue",
                            icon = Icons.Default.AddPhotoAlternate,
                            onClick = { importPhotosLauncher.launch(arrayOf("image/*")) },
                            testTag = "settings_import_photos_row"
                        )

                        SettingsDivider()

                        // Export photos to folder
                        SettingsActionRow(
                            title = "Export photos to folder",
                            subtitle = "Save all full-resolution photos and codes.csv to a device folder",
                            icon = Icons.Default.DriveFileMove,
                            onClick = { exportFolderLauncher.launch(null) },
                            testTag = "settings_export_folder_row"
                        )

                        SettingsDivider()

                        // Share codes as CSV
                        SettingsActionRow(
                            title = "Share codes as CSV",
                            subtitle = "Export readable list of coupon codes and statuses",
                            icon = Icons.Default.Share,
                            onClick = {
                                coroutineScope.launch {
                                    val intent = viewModel.getShareCsvIntent(context)
                                    context.startActivity(Intent.createChooser(intent, "Share codes CSV"))
                                }
                            },
                            testTag = "settings_share_csv_row"
                        )

                        SettingsDivider()

                        // Export app data (Backup)
                        SettingsActionRow(
                            title = "Export app data",
                            subtitle = "Complete backup zip of all coupons, original photos, and settings",
                            icon = Icons.Default.CloudUpload,
                            onClick = { showExportWarningDialog = true },
                            testTag = "settings_export_backup_row"
                        )

                        SettingsDivider()

                        // Import app data (Restore)
                        SettingsActionRow(
                            title = "Import app data",
                            subtitle = "Restore coupons and photos from a CouponVault backup zip",
                            icon = Icons.Default.CloudDownload,
                            onClick = { importBackupLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                            testTag = "settings_import_backup_row"
                        )

                        SettingsDivider()

                        // Delete all redeemed (Danger Zone)
                        SettingsActionRow(
                            title = "Delete all redeemed",
                            subtitle = if (redeemedCount > 0) "Delete $redeemedCount redeemed coupons and unreferenced photos" else "No redeemed coupons to delete",
                            icon = Icons.Outlined.DeleteSweep,
                            tint = if (redeemedCount > 0) brickColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            titleColor = if (redeemedCount > 0) brickColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            enabled = redeemedCount > 0,
                            onClick = { showDeleteAllRedeemedDialog = true },
                            testTag = "settings_delete_all_redeemed_row"
                        )
                    }
                }
            }

            // Section 3: About
            item {
                SettingsSectionHeader(title = "About")
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "CouponVault",
                                fontFamily = TitleFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Version 1.0 (Build 1)",
                            fontFamily = UiFontFamily,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Private on-device Room SQLite • Database schema v4",
                            fontFamily = UiFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // --- Overlay Permission Dialog ---
    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showOverlayPermissionDialog = false
                pendingEnableOverlay = false
            },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Permission Required",
                    fontFamily = TitleFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "CouponVault needs 'Display over other apps' permission so the floating paste button can stay on top while you redeem coupons.",
                    fontFamily = UiFontFamily,
                    fontSize = 14.sp,
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
                    )
                ) {
                    Text("Open Settings", fontFamily = UiFontFamily)
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
            }
        )
    }

    // --- Delete All Redeemed Confirmation Dialog ---
    if (showDeleteAllRedeemedDialog) {
        DeleteRedeemedDialog(
            count = redeemedCount,
            onDismiss = { showDeleteAllRedeemedDialog = false },
            onConfirm = {
                showDeleteAllRedeemedDialog = false
                viewModel.deleteAllRedeemed()
            }
        )
    }

    // --- Export Warning Dialog ---
    if (showExportWarningDialog) {
        AlertDialog(
            onDismissRequest = { showExportWarningDialog = false },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Export app data",
                    fontFamily = TitleFontFamily,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "This will export all coupons, settings, and full-resolution original photos into a zip backup archive.\n\nWarning: The exported backup file contains your private coupon photos. Store it in a secure location.",
                    fontFamily = UiFontFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportWarningDialog = false
                        exportBackupLauncher.launch(DataBackupManager.createBackupFileName())
                    },
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("dialog_confirm_export_backup")
                ) {
                    Text("Choose location", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportWarningDialog = false }
                ) {
                    Text("Cancel", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // --- Restore Summary & Mode Selection Dialog ---
    pendingRestoreValidation?.let { validation ->
        AlertDialog(
            onDismissRequest = { pendingRestoreValidation = null },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Restore backup",
                    fontFamily = TitleFontFamily,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Backup from ${validation.formattedDate}:\n${validation.manifest.couponCount} coupons, ${validation.manifest.photoCount} photos",
                        fontFamily = UiFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Choose restore option:",
                        fontFamily = UiFontFamily,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Merge Option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreMode = RestoreMode.MERGE }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = restoreMode == RestoreMode.MERGE,
                            onClick = { restoreMode = RestoreMode.MERGE },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Merge (recommended)",
                                fontFamily = UiFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Add coupons not yet saved, skip duplicates, keep current data.",
                                fontFamily = UiFontFamily,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Replace Option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreMode = RestoreMode.REPLACE }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = restoreMode == RestoreMode.REPLACE,
                            onClick = { restoreMode = RestoreMode.REPLACE },
                            colors = RadioButtonDefaults.colors(selectedColor = brickColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Replace everything",
                                fontFamily = UiFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = brickColor
                            )
                            Text(
                                text = "Wipe all current coupons and photos, then restore this backup.",
                                fontFamily = UiFontFamily,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sourceUri = validation.sourceUri
                        if (sourceUri != null) {
                            if (restoreMode == RestoreMode.REPLACE) {
                                showReplaceWarningDialog = true
                            } else {
                                val target = pendingRestoreValidation
                                pendingRestoreValidation = null
                                viewModel.restoreBackup(context, sourceUri, RestoreMode.MERGE)
                            }
                        }
                    },
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("dialog_confirm_restore")
                ) {
                    Text(
                        text = if (restoreMode == RestoreMode.REPLACE) "Continue..." else "Restore",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreValidation = null }) {
                    Text("Cancel", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // --- Secondary Replace Everything Warning Dialog ---
    if (showReplaceWarningDialog) {
        AlertDialog(
            onDismissRequest = { showReplaceWarningDialog = false },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Replace all data?",
                    fontFamily = TitleFontFamily,
                    fontSize = 20.sp,
                    color = brickColor
                )
            },
            text = {
                Text(
                    text = "This will permanently delete all existing coupons and photos on this device before restoring the backup. Consider exporting a backup first.\n\nAre you sure you want to proceed?",
                    fontFamily = UiFontFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sourceUri = pendingRestoreValidation?.sourceUri
                        showReplaceWarningDialog = false
                        pendingRestoreValidation = null
                        if (sourceUri != null) {
                            viewModel.restoreBackup(context, sourceUri, RestoreMode.REPLACE)
                        }
                    },
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brickColor,
                        contentColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.testTag("dialog_confirm_replace_all")
                ) {
                    Text("Wipe and Restore", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceWarningDialog = false }) {
                    Text("Cancel", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // --- Validation Error Dialog ---
    validationError?.let { errorReason ->
        AlertDialog(
            onDismissRequest = { validationError = null },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Invalid Backup File",
                    fontFamily = TitleFontFamily,
                    color = brickColor
                )
            },
            text = {
                Text(
                    text = "This file isn't a valid CouponVault backup:\n$errorReason",
                    fontFamily = UiFontFamily,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { validationError = null },
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("OK", fontFamily = UiFontFamily)
                }
            }
        )
    }

    // --- Backup / Restore Progress Dialog ---
    backupProgress?.let { progress ->
        Dialog(onDismissRequest = { /* Non-dismissible while writing */ }) {
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = if (progress.isExport) "Exporting backup..." else "Restoring backup...",
                        fontFamily = TitleFontFamily,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${progress.current} of ${progress.total}",
                            fontFamily = UiFontFamily,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${progress.percent}%",
                            fontFamily = UiFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (progress.isExport) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.cancelBackupProgress() }) {
                                Text("Cancel", fontFamily = UiFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontFamily = UiFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outline,
        thickness = 0.5.dp
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = title,
                fontFamily = UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontFamily = UiFontFamily,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontFamily = UiFontFamily,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
