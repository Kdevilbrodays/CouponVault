package com.example.couponvault.camera

import android.content.Context
import android.net.Uri
import com.example.couponvault.util.OcrCodeSelector
import com.example.couponvault.util.OcrTokenCandidate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TextRecognitionHelper {

    // Bundled offline Latin script text recognition
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Recognizes text from an image file and extracts candidate coupon tokens with their bounding boxes.
     */
    suspend fun recognizeTextCandidates(context: Context, imageFile: File): List<OcrTokenCandidate> =
        suspendCancellableCoroutine { continuation ->
            try {
                val inputImage = InputImage.fromFilePath(context, Uri.fromFile(imageFile))
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val candidates = mutableListOf<OcrTokenCandidate>()
                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                candidates.addAll(
                                    OcrCodeSelector.extractCandidatesFromText(
                                        text = line.text,
                                        boundingBox = line.boundingBox
                                    )
                                )
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resume(candidates)
                        }
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(exception)
                        }
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
}
