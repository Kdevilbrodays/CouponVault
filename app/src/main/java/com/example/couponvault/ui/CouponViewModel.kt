package com.example.couponvault.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.couponvault.camera.BarcodeScannerHelper
import com.example.couponvault.camera.TextRecognitionHelper
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
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

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _lastCapturedImagePath = MutableStateFlow<String?>(null)
    val lastCapturedImagePath: StateFlow<String?> = _lastCapturedImagePath.asStateFlow()

    private val _captureFeedback = MutableStateFlow<CaptureFeedback?>(null)
    val captureFeedback: StateFlow<CaptureFeedback?> = _captureFeedback.asStateFlow()

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
    fun capturePhoto(context: Context, imageCapture: ImageCapture) {
        if (_isCapturing.value) return
        _isCapturing.value = true

        val outputFile = StorageHelper.createOriginalFile(context)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    _isCapturing.value = false
                    _sessionPhotoCount.value += 1
                    _lastCapturedImagePath.value = outputFile.absolutePath

                    viewModelScope.launch {
                        processSavedPhoto(context, outputFile)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    _captureFeedback.value = CaptureFeedback(
                        message = "Capture failed: ${exception.localizedMessage ?: "Unknown error"}",
                        isSuccess = false
                    )
                }
            }
        )
    }

    // --- Import Existing Gallery Photos ---
    fun importGalleryPhotos(
        context: Context,
        uris: List<Uri>,
        onFinished: (importedUris: List<Uri>) -> Unit
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val total = uris.size
            var savedCount = 0
            var duplicateCount = 0
            var needsReviewCount = 0

            _batchProgress.value = BatchProgress(current = 0, total = total, title = "Importing gallery photos...")

            uris.forEachIndexed { index, uri ->
                _batchProgress.value = BatchProgress(
                    current = index + 1,
                    total = total,
                    title = "Importing ${index + 1} of $total..."
                )
                try {
                    val privateFile = StorageHelper.copyUriToPrivateOriginal(context, uri)
                    val barcodes = BarcodeScannerHelper.scanBarcodesWithBounds(context, privateFile)
                    val ocrCandidates = TextRecognitionHelper.recognizeTextCandidates(context, privateFile)
                    val result = repository.processWithQrAndOcr(privateFile, barcodes, ocrCandidates)

                    when (result) {
                        is ProcessResult.Saved -> savedCount++
                        is ProcessResult.SavedMultiple -> savedCount += result.codes.size
                        is ProcessResult.AlreadySaved -> duplicateCount++
                        is ProcessResult.Mixed -> {
                            savedCount += result.savedCodes.size
                            duplicateCount += result.duplicateCodes.size
                        }
                        is ProcessResult.NoCodeFound -> needsReviewCount++
                        is ProcessResult.Error -> {}
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
                onFinished(uris)
            }
        }
    }

    fun getGalleryDeletePendingIntent(context: Context, uris: List<Uri>): PendingIntent? {
        return StorageHelper.createGalleryDeleteRequest(context.contentResolver, uris)
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
            val ocrCandidates = TextRecognitionHelper.recognizeTextCandidates(context, file)
            val result = repository.processWithQrAndOcr(file, barcodes, ocrCandidates)

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
