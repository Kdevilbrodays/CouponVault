package com.example.couponvault.data

import android.content.Context
import android.graphics.Rect
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.couponvault.camera.BarcodeItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundExtractionManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var couponDao: CouponDao
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "test_photos_${System.currentTimeMillis()}")
        testDir.mkdirs()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        couponDao = db.couponDao()
    }

    @After
    fun teardown() {
        db.close()
        testDir.deleteRecursively()
    }

    private fun createDummyFile(name: String): File {
        val f = File(testDir, name)
        f.writeText("test image bytes")
        return f
    }

    @Test
    fun testSuccessfulExtractionUpdatesToUnscannedAndDone() = runBlocking {
        val file = createDummyFile("photo1.jpg")
        val coupon = Coupon(
            code = null,
            imagePath = file.absolutePath,
            status = CouponStatus.NEEDS_REVIEW,
            source = CouponSource.MANUAL,
            extractionState = ExtractionState.PENDING
        )
        val couponId = couponDao.insert(coupon)

        val manager = BackgroundExtractionManager(
            context = context,
            couponDao = couponDao,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            scanner = { _, _ ->
                listOf(
                    BarcodeItem(
                        rawValue = "https://havells.com/c/A51PEWKCWB81MXN",
                        boundingBox = Rect(10, 10, 200, 200)
                    )
                )
            }
        )

        manager.enqueue(couponId)

        // Wait for background worker to process
        var updated: Coupon? = null
        for (i in 1..20) {
            delay(50)
            updated = couponDao.getCouponById(couponId)
            if (updated?.extractionState == ExtractionState.DONE) break
        }

        assertNotNull(updated)
        assertEquals(ExtractionState.DONE, updated?.extractionState)
        assertEquals(CouponStatus.UNSCANNED, updated?.status)
        assertEquals(CouponSource.QR, updated?.source)
        assertEquals("A51PEWKCWB81MXN", updated?.code)
    }

    @Test
    fun testDuplicateCodeRemovesNewRowAndEmitsDuplicateSkipped() = runBlocking {
        // 1. Insert an existing coupon
        val file1 = createDummyFile("existing.jpg")
        val existingCoupon = Coupon(
            code = "DUPLICATE12345",
            imagePath = file1.absolutePath,
            status = CouponStatus.UNSCANNED,
            source = CouponSource.QR,
            extractionState = ExtractionState.DONE
        )
        val existingId = couponDao.insert(existingCoupon)

        // 2. Insert new pending coupon
        val file2 = createDummyFile("duplicate.jpg")
        val newCoupon = Coupon(
            code = null,
            imagePath = file2.absolutePath,
            status = CouponStatus.NEEDS_REVIEW,
            source = CouponSource.MANUAL,
            extractionState = ExtractionState.PENDING
        )
        val newId = couponDao.insert(newCoupon)

        val eventsList = mutableListOf<ExtractionEvent>()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val manager = BackgroundExtractionManager(
            context = context,
            couponDao = couponDao,
            scope = testScope,
            scanner = { _, _ ->
                listOf(
                    BarcodeItem(
                        rawValue = "DUPLICATE12345",
                        boundingBox = Rect(10, 10, 200, 200)
                    )
                )
            }
        )

        val collectJob = testScope.launch {
            manager.events.collect { eventsList.add(it) }
        }

        manager.enqueue(newId)

        // Wait for worker to finish
        for (i in 1..20) {
            delay(50)
            if (couponDao.getCouponById(newId) == null) break
        }

        // The duplicate coupon row should be deleted from DB
        assertNull("Duplicate coupon row must be deleted", couponDao.getCouponById(newId))

        // The existing coupon row must remain intact
        assertNotNull("Existing coupon must remain intact", couponDao.getCouponById(existingId))

        // The unreferenced new file should be deleted
        assertFalse("Unreferenced file must be deleted", file2.exists())
        assertTrue("Referenced existing file must remain", file1.exists())

        // Duplicate event emitted
        assertTrue(eventsList.any { it is ExtractionEvent.DuplicateSkipped && it.code == "DUPLICATE12345" })

        collectJob.cancel()
    }

    @Test
    fun testNoCodeFoundStaysNeedsReviewAndMarksDone() = runBlocking {
        val file = createDummyFile("no_code.jpg")
        val coupon = Coupon(
            code = null,
            imagePath = file.absolutePath,
            status = CouponStatus.NEEDS_REVIEW,
            source = CouponSource.MANUAL,
            extractionState = ExtractionState.PENDING
        )
        val couponId = couponDao.insert(coupon)

        val manager = BackgroundExtractionManager(
            context = context,
            couponDao = couponDao,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            scanner = { _, _ -> emptyList() } // No barcodes detected
        )

        manager.enqueue(couponId)

        var updated: Coupon? = null
        for (i in 1..20) {
            delay(50)
            updated = couponDao.getCouponById(couponId)
            if (updated?.extractionState == ExtractionState.DONE) break
        }

        assertNotNull(updated)
        assertEquals(ExtractionState.DONE, updated?.extractionState)
        assertEquals(CouponStatus.NEEDS_REVIEW, updated?.status)
        assertNull(updated?.code)
    }

    @Test
    fun testRestartRecoveryRequeuesPendingRows() = runBlocking {
        // Create 2 pending rows before manager starts (simulating app killed while photos pending)
        val file1 = createDummyFile("pending1.jpg")
        val file2 = createDummyFile("pending2.jpg")

        val id1 = couponDao.insert(
            Coupon(
                code = null,
                imagePath = file1.absolutePath,
                status = CouponStatus.NEEDS_REVIEW,
                source = CouponSource.MANUAL,
                extractionState = ExtractionState.PENDING
            )
        )
        val id2 = couponDao.insert(
            Coupon(
                code = null,
                imagePath = file2.absolutePath,
                status = CouponStatus.NEEDS_REVIEW,
                source = CouponSource.MANUAL,
                extractionState = ExtractionState.PENDING
            )
        )

        // Starting manager automatically re-queues any PENDING rows
        val manager = BackgroundExtractionManager(
            context = context,
            couponDao = couponDao,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            scanner = { _, file ->
                val code = if (file.name.contains("pending1")) "CODEAAA11111" else "CODEBBB22222"
                listOf(BarcodeItem(rawValue = code, boundingBox = Rect(0, 0, 100, 100)))
            }
        )

        // Wait for recovery processing to complete
        for (i in 1..30) {
            delay(50)
            val c1 = couponDao.getCouponById(id1)
            val c2 = couponDao.getCouponById(id2)
            if (c1?.extractionState == ExtractionState.DONE && c2?.extractionState == ExtractionState.DONE) {
                break
            }
        }

        val c1 = couponDao.getCouponById(id1)
        val c2 = couponDao.getCouponById(id2)
        assertEquals(ExtractionState.DONE, c1?.extractionState)
        assertEquals("CODEAAA11111", c1?.code)
        assertEquals(ExtractionState.DONE, c2?.extractionState)
        assertEquals("CODEBBB22222", c2?.code)
    }
}
