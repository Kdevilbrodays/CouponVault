package com.example.couponvault.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.couponvault.CouponVaultApplication
import com.example.couponvault.camera.BarcodeScannerHelper
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionEvent
import com.example.couponvault.data.ExtractionState
import com.example.couponvault.data.ProcessResult
import com.example.couponvault.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import android.provider.Settings
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.OverlayPreferences
import com.example.couponvault.data.QueueState
import com.example.couponvault.overlay.FloatingOverlayService
import kotlinx.coroutines.flow.first

data class CaptureFeedback(
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val isSuccess: Boolean = true,
    val isWarning: Boolean = false,
    val highlightedCouponId: Long? = null
)

data class BatchProgress(
    val current: Int,
    val total: Int,
    val title: String = ""
)

class CouponViewModel(
    private val repository: CouponRepository,
    private val queueManager: CouponQueueManager,
    private val preferences: OverlayPreferences
) : ViewModel() {

    val queueState: StateFlow<QueueState> = queueManager.queueState

    val isOverlayEnabled: StateFlow<Boolean> = preferences.overlayEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isServiceRunning: StateFlow<Boolean> = FloatingOverlayService.isRunning

    val autoMarkRedeemed: StateFlow<Boolean> = preferences.autoMarkRedeemedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAutoMarkRedeemed(enabled: Boolean) {
        viewModelScope.launch {
            queueManager.setAutoMarkRedeemed(enabled)
        }
    }

    fun setOverlayEnabled(enabled: Boolean, context: Context) {
        if (enabled) {
            if (Settings.canDrawOverlays(context)) {
                FloatingOverlayService.start(context)
                viewModelScope.launch { preferences.setOverlayEnabled(true) }
            }
        } else {
            FloatingOverlayService.stop(context)
            viewModelScope.launch { preferences.setOverlayEnabled(false) }
        }
    }

    fun resyncOverlayState(context: Context) {
        viewModelScope.launch {
            val canDraw = Settings.canDrawOverlays(context)
            val serviceRunning = FloatingOverlayService.isRunning.value
            val prefEnabled = preferences.overlayEnabledFlow.first()
            if (!canDraw || (!serviceRunning && prefEnabled)) {
                preferences.setOverlayEnabled(false)
                FloatingOverlayService.stop(context)
            }
        }
    }

    private val _selectedFilter = MutableStateFlow<CouponStatus?>(null)
    val selectedFilter: StateFlow<CouponStatus?> = _selectedFilter.asStateFlow()

    private val _highlightedCouponId = MutableStateFlow<Long?>(null)
    val highlightedCouponId: StateFlow<Long?> = _highlightedCouponId.asStateFlow()

    // Session-based photo counter for batch capture mode
    private val _sessionPhotoCount = MutableStateFlow(0)
    val sessionPhotoCount: StateFlow<Int> = _sessionPhotoCount.asStateFlow()

    private val _inFlightSaves = MutableStateFlow(0)
    val inFlightSaves: StateFlow<Int> = _inFlightSaves.asStateFlow()

    private val _sessionCouponIds = MutableStateFlow<List<Long>>(emptyList())
    val sessionCouponIds: StateFlow<List<Long>> = _sessionCouponIds.asStateFlow()

    val sessionCoupons: StateFlow<List<Coupon>> = combine(
        repository.allCoupons,
        _sessionCouponIds
    ) { all, ids ->
        ids.mapNotNull { id -> all.find { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastCapturedCoupon: StateFlow<Coupon?> = combine(
        sessionCoupons
    ) { (list) ->
        list.lastOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _lastCapturedImagePath = MutableStateFlow<String?>(null)
    val lastCapturedImagePath: StateFlow<String?> = _lastCapturedImagePath.asStateFlow()

    private val _captureFeedback = MutableStateFlow<CaptureFeedback?>(null)
    val captureFeedback: StateFlow<CaptureFeedback?> = _captureFeedback.asStateFlow()

    // Crash report state
    private val _crashReport = MutableStateFlow<String?>(null)
    val crashReport: StateFlow<String?> = _crashReport.asStateFlow()

    // Pending gallery delete state
    private val _pendingDeleteUris = MutableStateFlow<List<Uri>>(emptyList())
    val pendingDeleteUris: StateFlow<List<Uri>> = _pendingDeleteUris.asStateFlow()

    private val _showDeleteGalleryDialog = MutableStateFlow(false)
    val showDeleteGalleryDialog: StateFlow<Boolean> = _showDeleteGalleryDialog.asStateFlow()

    init {
        checkForPreviousCrash()
        listenToExtractionEvents()
    }

    private fun listenToExtractionEvents() {
        viewModelScope.launch {
            CouponVaultApplication.instance.extractionManager.events.collect { event ->
                when (event) {
                    is ExtractionEvent.DuplicateSkipped -> {
                        _captureFeedback.value = CaptureFeedback(
                            message = "Duplicate skipped",
                            isSuccess = true,
                            isWarning = true
                        )
                    }
                    is ExtractionEvent.CodeFound -> {
                        // Background extraction found code, Room automatically updates
                    }
                    is ExtractionEvent.NoCodeFound -> {
                        // Background extraction found no code
                    }
                }
            }
        }
    }

    private fun checkForPreviousCrash() {
        try {
            val crashFile = File(CouponVaultApplication.instance.filesDir, CouponVaultApplication.CRASH_FILE_NAME)
            if (crashFile.exists()) {
                val content = crashFile.readText()
                crashFile.delete()
                if (content.isNotBlank()) {
                    _crashReport.value = content
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CouponViewModel", "Failed to check last_crash.txt", e)
        }
    }

    fun dismissCrashReport() {
        _crashReport.value = null
    }

    // Progress for Gallery Import & Folder Export
    private val _batchProgress = MutableStateFlow<BatchProgress?>(null)
    val batchProgress: StateFlow<BatchProgress?> = _batchProgress.asStateFlow()

    // Bulk selection state
    private val _selectedCouponIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedCouponIds: StateFlow<Set<Long>> = _selectedCouponIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = combine(_selectedCouponIds) { (selected) ->
        selected.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val unscannedCount: StateFlow<Int> = repository.unscannedCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val needsReviewCount: StateFlow<Int> = repository.needsReviewCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val needsReviewCoupons: StateFlow<List<Coupon>> = repository.needsReviewCoupons
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val coupons: StateFlow<List<Coupon>> = combine(
        repository.allCoupons,
        _selectedFilter
    ) { all, filter ->
        if (filter == null) {
            all
        } else {
            all.filter { it.status == filter }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setFilter(filter: CouponStatus?) {
        _selectedFilter.value = filter
    }

    fun clearHighlight() {
        _highlightedCouponId.value = null
    }

    fun dismissFeedback() {
        _captureFeedback.value = null
    }

    fun resetSessionCount() {
        _sessionPhotoCount.value = 0
    }

    // --- Bulk Selection Operations ---
    fun toggleCouponSelection(id: Long) {
        val current = _selectedCouponIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedCouponIds.value = current
    }

    fun selectAllCoupons() {
        val allIds = coupons.value.map { it.id }.toSet()
        _selectedCouponIds.value = allIds
    }

    fun clearCouponSelection() {
        _selectedCouponIds.value = emptySet()
    }

    fun bulkUpdateStatus(newStatus: CouponStatus) {
        val ids = _selectedCouponIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.updateStatusBulk(ids, newStatus)
            clearCouponSelection()
            _captureFeedback.value = CaptureFeedback(
                message = "Updated ${ids.size} coupons to ${newStatus.name.lowercase().replace('_', ' ')}",
                isSuccess = true
            )
        }
    }

    fun bulkDelete(onComplete: () -> Unit = {}) {
        val ids = _selectedCouponIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteCouponsBulk(ids)
            clearCouponSelection()
            _captureFeedback.value = CaptureFeedback(
                message = "Deleted ${ids.size} coupons",
                isSuccess = true
            )
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    // --- Camera Capture ---
    fun capturePhoto(
        context: Context,
        cameraController: CameraController,
        onTapAccepted: () -> Unit = {}
    ) {
        capturePhotoInternal(
            context = context,
            takePictureCall = { options, executor, cb -> cameraController.takePicture(options, executor, cb) },
            onTapAccepted = onTapAccepted
        )
    }

    fun capturePhoto(
        context: Context,
        imageCapture: ImageCapture,
        onTapAccepted: () -> Unit = {}
    ) {
        capturePhotoInternal(
            context = context,
            takePictureCall = { options, executor, cb -> imageCapture.takePicture(options, executor, cb) },
            onTapAccepted = onTapAccepted
        )
    }

    internal fun capturePhotoInternal(
        context: Context,
        takePictureCall: (ImageCapture.OutputFileOptions, java.util.concurrent.Executor, ImageCapture.OnImageSavedCallback) -> Unit,
        onTapAccepted: () -> Unit
    ) {
        if (_inFlightSaves.value >= 4) {
            // Briefly ignore taps if more than ~4 saves are still in flight
            return
        }

        _inFlightSaves.value += 1
        _sessionPhotoCount.value += 1
        onTapAccepted()

        val outputFile = StorageHelper.createOriginalFile(context)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        takePictureCall(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    _inFlightSaves.value = (_inFlightSaves.value - 1).coerceAtLeast(0)
                    _lastCapturedImagePath.value = outputFile.absolutePath

                    // Immediately insert Room row with NEEDS_REVIEW, empty code, and extractionState = PENDING
                    CouponVaultApplication.instance.applicationScope.launch(Dispatchers.IO) {
                        try {
                            val coupon = Coupon(
                                code = null,
                                imagePath = outputFile.absolutePath,
                                status = CouponStatus.NEEDS_REVIEW,
                                source = CouponSource.MANUAL,
                                extractionState = ExtractionState.PENDING,
                                createdAt = System.currentTimeMillis()
                            )
                            val newId = repository.insertCoupon(coupon)
                            withContext(Dispatchers.Main) {
                                _sessionCouponIds.value = _sessionCouponIds.value + newId
                            }
                            CouponVaultApplication.instance.extractionManager.enqueue(newId)
                        } catch (e: Exception) {
                            android.util.Log.e("CouponViewModel", "Failed to insert pending coupon", e)
                        }
                    }

                    _captureFeedback.value = CaptureFeedback(
                        message = "Saved",
                        isSuccess = true
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    _inFlightSaves.value = (_inFlightSaves.value - 1).coerceAtLeast(0)
                    _captureFeedback.value = CaptureFeedback(
                        message = "Capture failed: ${exception.localizedMessage ?: "Unknown error"}",
                        isSuccess = false
                    )
                }
            }
        )
    }

    fun finishSession(summaryMessage: String?) {
        _sessionPhotoCount.value = 0
        _sessionCouponIds.value = emptyList()
        if (summaryMessage != null) {
            _captureFeedback.value = CaptureFeedback(
                message = summaryMessage,
                isSuccess = true
            )
        }
    }

    // --- Import Existing Gallery Photos ---
    fun importGalleryPhotos(
        context: Context,
        uris: List<Uri>,
        onFinished: (deletableUris: List<Uri>) -> Unit = {}
    ) {
        if (uris.isEmpty()) return

        // Run copy + DB insert in application-scoped coroutine so screen closures don't cancel it
        CouponVaultApplication.instance.applicationScope.launch(Dispatchers.IO) {
            val total = uris.size
            var savedCount = 0
            var duplicateCount = 0
            var needsReviewCount = 0
            val verifiedDeletableUris = mutableListOf<Uri>()

            _batchProgress.value = BatchProgress(current = 0, total = total, title = "Importing gallery photos...")

            uris.forEachIndexed { index, uri ->
                _batchProgress.value = BatchProgress(
                    current = index + 1,
                    total = total,
                    title = "Importing ${index + 1} of $total..."
                )
                try {
                    // 1. Convert to MediaStore URI if possible
                    val mediaStoreUri = StorageHelper.convertToMediaStoreUri(context, uri)

                    // 2. Query source byte size for verification
                    val sourceByteSize = StorageHelper.getSourceByteSize(context, uri)

                    // 3. Copy to private originals directory with UUID filename
                    val privateFile = StorageHelper.copyUriToPrivateOriginal(context, uri)

                    // 4. Verify private copy
                    val copyVerified = StorageHelper.verifyCopy(context, uri, privateFile, sourceByteSize)
                    if (!copyVerified) {
                        android.util.Log.e("CouponViewModel", "Copy verification failed for URI $uri")
                        if (privateFile.exists()) {
                            privateFile.delete()
                        }
                        return@forEachIndexed
                    }

                    // 5. Scan barcodes and insert DB row
                    val barcodes = BarcodeScannerHelper.scanBarcodesWithBounds(context, privateFile)
                    val result = repository.processCouponPhoto(privateFile, barcodes)

                    var isKeptOrDuplicateVerified = false
                    when (result) {
                        is ProcessResult.Saved -> {
                            savedCount++
                            isKeptOrDuplicateVerified = true
                        }
                        is ProcessResult.SavedMultiple -> {
                            savedCount += result.codes.size
                            isKeptOrDuplicateVerified = true
                        }
                        is ProcessResult.AlreadySaved -> {
                            duplicateCount++
                            // Items reported as "already saved" count as verified if the existing copy is verified
                            val existingCoupon = repository.getCouponById(result.existingCouponId)
                            if (existingCoupon != null) {
                                val existingFile = File(existingCoupon.imagePath)
                                if (existingFile.exists() && existingFile.length() > 0L) {
                                    isKeptOrDuplicateVerified = true
                                }
                            }
                        }
                        is ProcessResult.Mixed -> {
                            savedCount += result.savedCodes.size
                            duplicateCount += result.duplicateCodes.size
                            isKeptOrDuplicateVerified = true
                        }
                        is ProcessResult.NoCodeFound -> {
                            needsReviewCount++
                            isKeptOrDuplicateVerified = true
                        }
                        is ProcessResult.Error -> {
                            android.util.Log.e("CouponViewModel", "Error processing coupon: ${result.message}")
                        }
                    }

                    // Only offer deletion if copy is verified AND converted to MediaStore URI
                    if (isKeptOrDuplicateVerified && mediaStoreUri != null) {
                        verifiedDeletableUris.add(mediaStoreUri)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CouponViewModel", "Failed to import photo from URI: $uri", e)
                }
            }

            _batchProgress.value = null

            val summaryMsg = buildString {
                append("Imported $total photos ($savedCount new")
                if (duplicateCount > 0) append(", $duplicateCount already saved")
                if (needsReviewCount > 0) append(", $needsReviewCount for review")
                append(")")
            }

            _captureFeedback.value = CaptureFeedback(
                message = summaryMsg,
                isSuccess = true
            )

            withContext(Dispatchers.Main) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && verifiedDeletableUris.isNotEmpty()) {
                    _pendingDeleteUris.value = verifiedDeletableUris
                    _showDeleteGalleryDialog.value = true
                } else if (uris.isNotEmpty()) {
                    _captureFeedback.value = CaptureFeedback(
                        message = "These photos can't be removed from the gallery automatically.",
                        isSuccess = true,
                        isWarning = true
                    )
                }
                onFinished(verifiedDeletableUris)
            }
        }
    }

    fun handleDeleteResult(resultCode: Int) {
        val count = _pendingDeleteUris.value.size
        _pendingDeleteUris.value = emptyList()
        _showDeleteGalleryDialog.value = false

        if (resultCode == android.app.Activity.RESULT_OK) {
            val msg = if (count == 1) "Deleted 1 original from the gallery" else "Deleted $count originals from the gallery"
            _captureFeedback.value = CaptureFeedback(
                message = msg,
                isSuccess = true
            )
        } else if (resultCode == android.app.Activity.RESULT_CANCELED) {
            _captureFeedback.value = CaptureFeedback(
                message = "Kept in gallery",
                isSuccess = true
            )
        }
    }

    fun dismissDeleteGalleryDialog() {
        _showDeleteGalleryDialog.value = false
        _pendingDeleteUris.value = emptyList()
        _captureFeedback.value = CaptureFeedback(
            message = "Kept in gallery",
            isSuccess = true
        )
    }

    fun onDeleteException(e: Exception) {
        android.util.Log.e("CouponViewModel", "Couldn't delete originals", e)
        _pendingDeleteUris.value = emptyList()
        _showDeleteGalleryDialog.value = false
        _captureFeedback.value = CaptureFeedback(
            message = "Couldn't delete originals",
            isSuccess = false
        )
    }

    // --- Export / Backup ---
    fun exportAllOriginals(
        context: Context,
        treeUri: Uri,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _batchProgress.value = BatchProgress(current = 0, total = 100, title = "Preparing export...")
            val allCouponsList = repository.getAllCouponsList()

            val result = StorageHelper.exportAllToTreeFolder(
                context = context,
                treeUri = treeUri,
                coupons = allCouponsList,
                onProgress = { current, total ->
                    _batchProgress.value = BatchProgress(
                        current = current,
                        total = total,
                        title = "Exporting $current of $total files..."
                    )
                }
            )

            _batchProgress.value = null

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val count = result.getOrNull() ?: 0
                    onResult(true, "Exported $count originals and codes.csv successfully!")
                } else {
                    onResult(false, result.exceptionOrNull()?.message ?: "Export failed")
                }
            }
        }
    }

    suspend fun getShareCsvIntent(context: Context): Intent = withContext(Dispatchers.IO) {
        val all = repository.getAllCouponsList()
        StorageHelper.createShareCsvIntent(context, all)
    }

    private suspend fun processSavedPhoto(context: Context, file: File) {
        try {
            val barcodes = BarcodeScannerHelper.scanBarcodesWithBounds(context, file)
            val result = repository.processCouponPhoto(file, barcodes)

            when (result) {
                is ProcessResult.Saved -> {
                    _highlightedCouponId.value = result.couponId
                    _captureFeedback.value = CaptureFeedback(
                        message = "Saved: ${result.code}",
                        isSuccess = true,
                        highlightedCouponId = result.couponId
                    )
                }

                is ProcessResult.SavedMultiple -> {
                    val firstId = result.couponIds.firstOrNull()
                    _highlightedCouponId.value = firstId
                    _captureFeedback.value = CaptureFeedback(
                        message = "Saved ${result.codes.size} codes: ${result.codes.joinToString(", ")}",
                        isSuccess = true,
                        highlightedCouponId = firstId
                    )
                }

                is ProcessResult.AlreadySaved -> {
                    _highlightedCouponId.value = result.existingCouponId
                    _captureFeedback.value = CaptureFeedback(
                        message = "Already saved: ${result.code}",
                        isSuccess = false,
                        isWarning = true,
                        highlightedCouponId = result.existingCouponId
                    )
                }

                is ProcessResult.Mixed -> {
                    _highlightedCouponId.value = result.highlightId
                    val msg = buildString {
                        append("Saved ${result.savedCodes.size} new")
                        if (result.duplicateCodes.isNotEmpty()) {
                            append(" (${result.duplicateCodes.size} already saved)")
                        }
                    }
                    _captureFeedback.value = CaptureFeedback(
                        message = msg,
                        isSuccess = true,
                        highlightedCouponId = result.highlightId
                    )
                }

                is ProcessResult.NoCodeFound -> {
                    _highlightedCouponId.value = result.couponId
                    _captureFeedback.value = CaptureFeedback(
                        message = "No code found, saved for review",
                        isSuccess = true,
                        isWarning = true,
                        highlightedCouponId = result.couponId
                    )
                }

                is ProcessResult.Error -> {
                    _captureFeedback.value = CaptureFeedback(
                        message = "Error: ${result.message}",
                        isSuccess = false
                    )
                }
            }
        } catch (e: Exception) {
            _captureFeedback.value = CaptureFeedback(
                message = "Failed to scan photo: ${e.message ?: "Unknown error"}",
                isSuccess = false
            )
        }
    }

    fun updateStatus(coupon: Coupon, newStatus: CouponStatus) {
        viewModelScope.launch {
            val redeemedTimestamp = if (newStatus == CouponStatus.REDEEMED) {
                coupon.redeemedAt ?: System.currentTimeMillis()
            } else {
                null
            }
            val updated = coupon.copy(
                status = newStatus,
                redeemedAt = redeemedTimestamp
            )
            repository.updateCoupon(updated)
        }
    }

    /**
     * Updates code with validation and duplicate checking.
     * Saving an edit sets source = MANUAL and status = UNSCANNED.
     */
    fun saveManualCode(
        coupon: Coupon,
        newCode: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = newCode.trim().uppercase()
            if (trimmed.isEmpty()) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Code cannot be empty")
                }
                return@launch
            }

            if (!trimmed.matches(Regex("^[A-Z0-9]{8,24}$"))) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Invalid format: code must be 8-24 alphanumeric characters")
                }
                return@launch
            }

            val existing = repository.getCouponByCode(trimmed)
            if (existing != null && existing.id != coupon.id) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Code already exists in coupon #${existing.id}")
                }
                return@launch
            }

            val updated = coupon.copy(
                code = trimmed,
                source = CouponSource.MANUAL,
                status = CouponStatus.UNSCANNED
            )
            repository.updateCoupon(updated)
            withContext(Dispatchers.Main) {
                onResult(true, "Code saved as Unscanned")
            }
        }
    }

    fun deleteCoupon(coupon: Coupon, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteCoupon(coupon)
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun saveToGallery(context: Context, coupon: Coupon, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(coupon.imagePath)
            val result = StorageHelper.saveOriginalToGallery(context, file)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    onResult(true, "Saved to gallery (Pictures/CouponVault)")
                } else {
                    onResult(false, result.exceptionOrNull()?.message ?: "Failed to save to gallery")
                }
            }
        }
    }

    fun getShareIntent(context: Context, coupon: Coupon): Intent {
        val file = File(coupon.imagePath)
        return StorageHelper.createShareIntent(context, file, coupon.code)
    }

    companion object {
        fun provideFactory(
            repository: CouponRepository,
            queueManager: CouponQueueManager,
            preferences: OverlayPreferences
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CouponViewModel(repository, queueManager, preferences) as T
                }
            }
    }
}
