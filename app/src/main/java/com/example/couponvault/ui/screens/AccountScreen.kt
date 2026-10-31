package com.example.couponvault.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.couponvault.sync.SyncState
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.theme.BrickError
import com.example.couponvault.ui.theme.ButtonShape
import com.example.couponvault.ui.theme.CardShape
import com.example.couponvault.ui.theme.FieldShape
import com.example.couponvault.ui.theme.PillShape
import com.example.couponvault.ui.theme.TitleFontFamily
import com.example.couponvault.ui.theme.UiFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: CouponViewModel,
    onNavigateBack: () -> Unit
) {
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val supabaseUrl by viewModel.supabaseUrl.collectAsStateWithLifecycle()
    val supabaseAnonKey by viewModel.supabaseAnonKey.collectAsStateWithLifecycle()
    val isRealtimeConnected by viewModel.isRealtimeConnected.collectAsStateWithLifecycle()
    val lastPushTime by viewModel.lastPushTime.collectAsStateWithLifecycle()
    val lastPullTime by viewModel.lastPullTime.collectAsStateWithLifecycle()
    val clockOffset by viewModel.clockOffset.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Account & Sync",
                        fontFamily = TitleFontFamily,
                        fontWeight = FontWeight.Normal,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("account_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val isSignedIn = !userEmail.isNullOrBlank()

            if (isSignedIn) {
                SignedInSection(
                    email = userEmail ?: "",
                    syncStatus = syncStatus,
                    isRealtimeConnected = isRealtimeConnected,
                    lastPushTime = lastPushTime,
                    lastPullTime = lastPullTime,
                    clockOffset = clockOffset,
                    lastError = lastError,
                    onSyncNow = { viewModel.syncNow() },
                    onFullResync = { callback -> viewModel.performFullResync(callback) },
                    onRequestSignOut = { showSignOutDialog = true }
                )

                PhotosSection(viewModel = viewModel)
            } else {
                SignedOutSection(
                    viewModel = viewModel,
                    serverConfigured = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()
                )
            }

            // Advanced: Server configuration section
            AdvancedServerSection(
                currentUrl = supabaseUrl,
                currentKey = supabaseAnonKey,
                onSaveConfig = { url, key ->
                    viewModel.updateServerConfig(url, key)
                }
            )
        }
    }

    if (showSignOutDialog) {
        SignOutConfirmationDialog(
            unsyncedCount = syncStatus.pendingCount,
            onDismiss = { showSignOutDialog = false },
            onSignOut = { wipeLocalData ->
                showSignOutDialog = false
                viewModel.signOut(wipeLocalData = wipeLocalData) {
                    onNavigateBack()
                }
            }
        )
    }
}

@Composable
private fun SignedInSection(
    email: String,
    syncStatus: com.example.couponvault.sync.SyncStatus,
    isRealtimeConnected: Boolean,
    lastPushTime: Long?,
    lastPullTime: Long?,
    clockOffset: Long,
    lastError: String?,
    onSyncNow: () -> Unit,
    onFullResync: ((Result<com.example.couponvault.sync.ReconcileSummary>) -> Unit) -> Unit,
    onRequestSignOut: () -> Unit
) {
    var isResyncing by remember { mutableStateOf(false) }
    var resyncMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // User header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(PillShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Signed in as",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = email,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                OutlinedButton(
                    onClick = onRequestSignOut,
                    shape = ButtonShape,
                    modifier = Modifier.testTag("sign_out_button")
                ) {
                    Text(
                        text = "Sign out",
                        fontFamily = UiFontFamily,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Sync Health & Diagnostics Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sync Health & Diagnostics",
                    fontFamily = TitleFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Realtime Connection Badge
                Surface(
                    shape = PillShape,
                    color = if (isRealtimeConnected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.testTag("realtime_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(PillShape)
                                .background(
                                    if (isRealtimeConnected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRealtimeConnected) "Realtime active" else "Realtime disconnected",
                            fontFamily = UiFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = if (isRealtimeConnected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Diagnostics Details Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = FieldShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncMetricRow(
                        label = "Realtime status",
                        value = if (isRealtimeConnected) "Connected (push + live pull)" else "Disconnected (30s polling fallback)"
                    )
                    SyncMetricRow(
                        label = "Last push",
                        value = formatTimestamp(lastPushTime)
                    )
                    SyncMetricRow(
                        label = "Last pull",
                        value = formatTimestamp(lastPullTime)
                    )
                    SyncMetricRow(
                        label = "Pending changes",
                        value = "${syncStatus.pendingCount} unsynced local rows"
                    )
                    SyncMetricRow(
                        label = "Clock offset",
                        value = "${if (clockOffset >= 0) "+$clockOffset" else "$clockOffset"} ms" +
                                if (clockOffset == 0L) " (synchronized)" else " relative to server"
                    )
                    if (syncStatus.skippedCount > 0) {
                        SyncMetricRow(
                            label = "Skipped rows",
                            value = "${syncStatus.skippedCount} invalid row(s) skipped",
                            isError = true
                        )
                    }
                    SyncMetricRow(
                        label = "Last error",
                        value = lastError ?: "None",
                        isError = lastError != null && lastError.isNotBlank()
                    )
                }
            }

            if (resyncMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = resyncMessage ?: "",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSyncNow,
                    enabled = syncStatus.state != SyncState.SYNCING && !isResyncing,
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sync_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (syncStatus.state == SyncState.SYNCING && !isResyncing) "Syncing..." else "Sync now",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = {
                        isResyncing = true
                        resyncMessage = null
                        onFullResync { result ->
                            isResyncing = false
                            if (result.isSuccess) {
                                val s = result.getOrThrow()
                                resyncMessage = "Full resync complete: Removed ${s.removedCount}, added ${s.addedCount}"
                            } else {
                                resyncMessage = "Full resync failed: ${result.exceptionOrNull()?.message}"
                            }
                        }
                    },
                    enabled = syncStatus.state != SyncState.SYNCING && !isResyncing,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("full_resync_button")
                ) {
                    if (isResyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (isResyncing) "Resyncing..." else "Full resync",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncMetricRow(
    label: String,
    value: String,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = UiFontFamily,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontFamily = UiFontFamily,
            fontSize = 12.sp,
            fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isError) BrickError else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0) return "Never"
    val sdf = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
private fun SignedOutSection(
    viewModel: CouponViewModel,
    serverConfigured: Boolean
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Sign in to sync",
                fontFamily = TitleFontFamily,
                fontWeight = FontWeight.Normal,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Keep your coupon vault backed up and accessible across your devices. Room database remains your local offline source of truth.",
                fontFamily = UiFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!serverConfigured) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = CardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Sync requires a server. Enter your Supabase URL and anon key in Advanced below.",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = CardShape,
                    color = BrickError.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, BrickError.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage!!,
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = BrickError,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    errorMessage = null
                },
                label = { Text("Email address", fontFamily = UiFontFamily) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                shape = FieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_email_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                label = { Text("Password", fontFamily = UiFontFamily) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                    }
                ),
                shape = FieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_password_input")
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (email.isBlank() || password.isBlank()) {
                            errorMessage = "Please enter both email and password."
                        } else {
                            isLoading = true
                            errorMessage = null
                            viewModel.signIn(email, password) { result ->
                                isLoading = false
                                if (result.isFailure) {
                                    errorMessage = result.exceptionOrNull()?.message ?: "Sign in failed"
                                }
                            }
                        }
                    },
                    enabled = !isLoading && serverConfigured,
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("account_sign_in_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Sign in", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (email.isBlank() || password.isBlank()) {
                            errorMessage = "Please enter both email and password."
                        } else {
                            isLoading = true
                            errorMessage = null
                            viewModel.signUp(email, password) { result ->
                                isLoading = false
                                if (result.isFailure) {
                                    errorMessage = result.exceptionOrNull()?.message ?: "Sign up failed"
                                }
                            }
                        }
                    },
                    enabled = !isLoading && serverConfigured,
                    shape = ButtonShape,
                    modifier = Modifier.testTag("account_sign_up_button")
                ) {
                    Text("Create account", fontFamily = UiFontFamily)
                }
            }
        }
    }
}

@Composable
private fun AdvancedServerSection(
    currentUrl: String,
    currentKey: String,
    onSaveConfig: (String, String) -> String?
) {
    var expanded by remember { mutableStateOf(false) }
    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    var keyInput by remember(currentKey) { mutableStateOf(currentKey) }
    var feedbackError by remember { mutableStateOf<String?>(null) }
    var feedbackSuccess by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Advanced: server",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (currentUrl.isNotBlank()) "Configured (${currentUrl.take(24)}...)" else "Not configured",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = "Enter your Supabase project URL and anon public key. The secret key is strictly prohibited.",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (feedbackError != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = feedbackError!!,
                            fontFamily = UiFontFamily,
                            fontSize = 12.sp,
                            color = BrickError
                        )
                    }

                    if (feedbackSuccess) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Server configuration saved",
                                fontFamily = UiFontFamily,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            feedbackError = null
                            feedbackSuccess = false
                        },
                        label = { Text("Supabase URL", fontFamily = UiFontFamily) },
                        placeholder = { Text("https://xyzcompany.supabase.co", fontFamily = UiFontFamily) },
                        singleLine = true,
                        shape = FieldShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("server_url_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it
                            feedbackError = null
                            feedbackSuccess = false
                        },
                        label = { Text("Anon / Public Key", fontFamily = UiFontFamily) },
                        placeholder = { Text("eyJhbGciOi...", fontFamily = UiFontFamily) },
                        singleLine = true,
                        shape = FieldShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("server_key_input")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val error = onSaveConfig(urlInput.trim(), keyInput.trim())
                            if (error != null) {
                                feedbackError = error
                                feedbackSuccess = false
                            } else {
                                feedbackError = null
                                feedbackSuccess = true
                            }
                        },
                        shape = ButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("save_server_button")
                    ) {
                        Text("Save server settings", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignOutConfirmationDialog(
    unsyncedCount: Int,
    onDismiss: () -> Unit,
    onSignOut: (wipeLocalData: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Sign out",
                fontFamily = TitleFontFamily,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                if (unsyncedCount > 0) {
                    Text(
                        text = "You have $unsyncedCount unsynced changes that will not be sent to the server.",
                        fontFamily = UiFontFamily,
                        color = BrickError,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "Do you want to keep your coupons on this phone, or remove them?",
                    fontFamily = UiFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSignOut(false) },
                shape = ButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("keep_local_button")
            ) {
                Text("Keep local coupons", fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("cancel_sign_out_button")
                ) {
                    Text("Cancel", fontFamily = UiFontFamily)
                }
                TextButton(
                    onClick = { onSignOut(true) },
                    modifier = Modifier.testTag("remove_local_button")
                ) {
                    Text("Remove", fontFamily = UiFontFamily, color = BrickError)
                }
            }
        },
        shape = CardShape,
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun PhotosSection(
    viewModel: CouponViewModel
) {
    val photoSyncEnabled by viewModel.photoSyncEnabled.collectAsStateWithLifecycle()
    val photoWifiOnly by viewModel.photoWifiOnly.collectAsStateWithLifecycle()
    val photoUploadQuality by viewModel.photoUploadQuality.collectAsStateWithLifecycle()
    val isDownloadingAll by viewModel.isDownloadingAllPhotos.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadAllPhotosProgress.collectAsStateWithLifecycle()

    var downloadStatusText by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("photos_sync_card"),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Photos",
                    fontFamily = TitleFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // [Sync photos] toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sync photos",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Back up and sync coupon photos with your private cloud vault",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = photoSyncEnabled,
                    onCheckedChange = { viewModel.setPhotoSyncEnabled(it) },
                    modifier = Modifier.testTag("photo_sync_toggle")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // [Wi-Fi only] toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wi-Fi only",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Upload photos only when connected to an unmetered Wi-Fi network",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = photoWifiOnly,
                    onCheckedChange = { viewModel.setPhotoWifiOnly(it) },
                    enabled = photoSyncEnabled,
                    modifier = Modifier.testTag("photo_wifi_only_toggle")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // [Upload quality]: Original (default) or Reduced
            Text(
                text = "Upload quality",
                fontFamily = UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QualityChoicePill(
                    label = "Original",
                    selected = photoUploadQuality == "original",
                    enabled = photoSyncEnabled,
                    onClick = { viewModel.setPhotoUploadQuality("original") },
                    modifier = Modifier.weight(1f).testTag("quality_original_button")
                )
                QualityChoicePill(
                    label = "Reduced",
                    selected = photoUploadQuality == "reduced",
                    enabled = photoSyncEnabled,
                    onClick = { viewModel.setPhotoUploadQuality("reduced") },
                    modifier = Modifier.weight(1f).testTag("quality_reduced_button")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Free plans have limited storage. Reduced uploads save space, and other devices will get the reduced copy.",
                fontFamily = UiFontFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // [Download all photos] button with progress count
            OutlinedButton(
                onClick = {
                    downloadStatusText = null
                    viewModel.downloadAllPhotos(
                        onProgress = { _, _ -> },
                        onComplete = { count ->
                            downloadStatusText = if (count > 0) "Downloaded $count photo${if (count != 1) "s" else ""}." else "All photos are already on this device."
                        }
                    )
                },
                enabled = !isDownloadingAll,
                modifier = Modifier.fillMaxWidth().testTag("download_all_photos_button"),
                shape = ButtonShape
            ) {
                if (isDownloadingAll) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val progress = downloadProgress
                    val progressLabel = if (progress != null) "Downloading ${progress.first}/${progress.second}..." else "Downloading..."
                    Text(
                        text = progressLabel,
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download all photos",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (!downloadStatusText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = downloadStatusText ?: "",
                    fontFamily = UiFontFamily,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun QualityChoicePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontFamily = UiFontFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
