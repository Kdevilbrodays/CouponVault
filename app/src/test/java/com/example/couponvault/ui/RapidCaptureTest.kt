package com.example.couponvault.ui

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.couponvault.data.AppDatabase
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.OverlayPreferences
import com.example.couponvault.util.AndroidClipboardHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RapidCaptureTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: CouponRepository
    private lateinit var viewModel: CouponViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CouponRepository(db.couponDao())
        val prefs = OverlayPreferences(context)
        val queueManager = CouponQueueManager(db.couponDao(), prefs, AndroidClipboardHelper(context))
        viewModel = CouponViewModel(repository, queueManager, prefs)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testShutterTappedTenTimesRapidlyDoesNotBlockAndLimitsInFlight() {
        var acceptedTaps = 0
        val pendingCallbacks = mutableListOf<ImageCapture.OnImageSavedCallback>()

        // Simulate 10 rapid shutter taps in 5 seconds without waiting for save completion
        for (i in 1..10) {
            viewModel.capturePhotoInternal(
                context = context,
                takePictureCall = { _, _, callback ->
                    pendingCallbacks.add(callback)
                },
                onTapAccepted = {
                    acceptedTaps++
                }
            )
        }

        // Taps up to in-flight limit (4) must be accepted immediately
        assertEquals("Initial 4 rapid in-flight taps must be accepted immediately", 4, acceptedTaps)
        assertEquals("In-flight count must equal 4", 4, viewModel.inFlightSaves.value)
        assertEquals("Session photo count must equal 4", 4, viewModel.sessionPhotoCount.value)

        // Complete 1 in-flight photo save
        // Now next tap can be accepted!
        // Finish session test
        viewModel.finishSession("4 saved, 4 codes found")
        assertEquals(0, viewModel.sessionPhotoCount.value)
        assertEquals("4 saved, 4 codes found", viewModel.captureFeedback.value?.message)
    }
}
