package com.example.couponvault.ui

import android.content.Context
import android.content.Intent
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.couponvault.camera.BarcodeScannerHelper
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

data class CaptureFeedback(
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val isSuccess: Boolean = true,
    val isWarning: Boolean = false,
    val highlightedCouponId: Long? = null
)

class CouponViewModel(
    private val repository: CouponRepository
) : ViewModel() {

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

    val unscannedCount: StateFlow<Int> = repository.unscannedCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
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

    /**
     * Executes batch capture:
     * - Takes full-resolution photo to private filesDir/originals/
     * - Updates session count immediately
     * - Decodes QR barcode in background via offline ML Kit
     * - Updates Room database and posts snackbar feedback
     */
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

                    // Background decode and storage
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

    private suspend fun processSavedPhoto(context: Context, file: File) {
        try {
            val rawCodes = BarcodeScannerHelper.scanQrCodes(context, file)
            val result = repository.processCapturedPhoto(file, rawCodes)

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

    fun updateCode(coupon: Coupon, newCode: String) {
        viewModelScope.launch {
            val trimmed = newCode.trim().uppercase()
            val updated = coupon.copy(
                code = if (trimmed.isEmpty()) null else trimmed,
                source = if (coupon.source == CouponSource.QR && coupon.code == null) CouponSource.MANUAL else coupon.source
            )
            repository.updateCoupon(updated)
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
        fun provideFactory(repository: CouponRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CouponViewModel(repository) as T
                }
            }
    }
}
