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
                    onSyncNow = { viewModel.syncNow() },
                    onRequestSignOut = { showSignOutDialog = true }
                )
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
    onSyncNow: () -> Unit,
    onRequestSignOut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sync Status Row
            val statusColor = when (syncStatus.state) {
                SyncState.SYNCING -> MaterialTheme.colorScheme.primary
                SyncState.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
                SyncState.ERROR -> BrickError
                SyncState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            val statusText = when (syncStatus.state) {
                SyncState.SYNCING -> "Syncing with cloud..."
                SyncState.OFFLINE -> "Offline • Changes queued"
                SyncState.ERROR -> syncStatus.errorMessage ?: "Sync error"
                SyncState.IDLE -> {
                    val last = syncStatus.lastSyncedAt
                    if (last != null && last > 0) {
                        val formatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(last))
                        "Last synced: $formatted"
                    } else {
                        "Ready to sync"
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (syncStatus.state) {
                    SyncState.SYNCING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = statusColor
                        )
                    }
                    SyncState.OFFLINE -> {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    SyncState.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    SyncState.IDLE -> {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = statusText,
                    fontFamily = UiFontFamily,
                    fontSize = 13.sp,
                    color = statusColor
                )

                if (syncStatus.pendingCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${syncStatus.pendingCount} pending",
                        fontFamily = UiFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSyncNow,
                    enabled = syncStatus.state != SyncState.SYNCING,
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
                        text = if (syncStatus.state == SyncState.SYNCING) "Syncing..." else "Sync now",
                        fontFamily = UiFontFamily,
                        fontWeight = FontWeight.SemiBold
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
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
