package com.example.couponvault.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.couponvault.util.StorageHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var couponDao: CouponDao
    private lateinit var repository: CouponRepository
    private lateinit var preferences: OverlayPreferences
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "backup_test_${System.currentTimeMillis()}").apply { mkdirs() }

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        couponDao = db.couponDao()
        repository = CouponRepository(couponDao)
        preferences = OverlayPreferences(context)
    }

    @After
    fun teardown() {
        db.close()
        testDir.deleteRecursively()
    }

    private fun createPhotoFile(name: String, content: String = "photo_data"): File {
        val originalsDir = StorageHelper.getOriginalsDirectory(context)
        val file = File(originalsDir, name)
        file.writeText(content)
        return file
    }

    @Test
    fun testExportAndRestoreRoundTripWithReplace() = runTest {
        // 1. Create sample photos and coupons
        val photo1 = createPhotoFile("coupon_roundtrip_1.jpg", "image_bytes_1")
        val photo2 = createPhotoFile("coupon_roundtrip_2.jpg", "image_bytes_2")

        val c1 = Coupon(
            code = "SAVE20OFF",
            imagePath = photo1.absolutePath,
            status = CouponStatus.UNSCANNED,
            source = CouponSource.QR,
            createdAt = 1700000000000L,
            extractionState = ExtractionState.DONE
        )
        val c2 = Coupon(
            code = "FREESHIP",
            imagePath = photo2.absolutePath,
            status = CouponStatus.REDEEMED,
            source = CouponSource.MANUAL,
            createdAt = 1700000050000L,
            redeemedAt = 1700000100000L,
            extractionState = ExtractionState.DONE
        )

        val id1 = couponDao.insert(c1)
        val id2 = couponDao.insert(c2)
        preferences.setAutoMarkRedeemed(true)

        // 2. Export backup to byte stream
        val zipBytesOut = ByteArrayOutputStream()
        val exportResult = DataBackupManager.exportBackup(
            context = context,
            outputStream = zipBytesOut,
            repository = repository,
            preferences = preferences
        )
        assertTrue("Export should succeed", exportResult.isSuccess)
        val zipBytes = zipBytesOut.toByteArray()
        assertTrue("Exported zip should not be empty", zipBytes.isNotEmpty())

        // 3. Validate backup without modifying data
        val validateResult = DataBackupManager.validateBackup(ByteArrayInputStream(zipBytes))
        assertTrue("Validation should succeed", validateResult.isSuccess)
        val validation = validateResult.getOrThrow()
        assertEquals(2, validation.manifest.couponCount)
        assertEquals(2, validation.manifest.photoCount)
        assertEquals(4, validation.manifest.dbSchemaVersion)

        // 4. Wipe current database
        couponDao.deleteAll()
        assertEquals(0, couponDao.getAllCouponsList().size)

        // 5. Restore with REPLACE mode
        val restoreResult = DataBackupManager.restoreBackup(
            context = context,
            inputStream = ByteArrayInputStream(zipBytes),
            mode = RestoreMode.REPLACE,
            database = db,
            preferences = preferences
        )
        assertTrue("Restore should succeed", restoreResult.isSuccess)
        val r = restoreResult.getOrThrow()
        assertEquals(2, r.importedCount)
        assertEquals(0, r.skippedDuplicates)

        // 6. Assert restored data is identical
        val restoredCoupons = couponDao.getAllCouponsList()
        assertEquals(2, restoredCoupons.size)

        val restored1 = couponDao.getCouponByCode("SAVE20OFF")
        assertNotNull("SAVE20OFF should exist", restored1)
        assertEquals(CouponStatus.UNSCANNED, restored1!!.status)
        assertEquals(CouponSource.QR, restored1.source)
        assertEquals(1700000000000L, restored1.createdAt)
        assertEquals(ExtractionState.DONE, restored1.extractionState)
        assertTrue("Restored photo file should exist", File(restored1.imagePath).exists())
        assertEquals("image_bytes_1", File(restored1.imagePath).readText())

        val restored2 = couponDao.getCouponByCode("FREESHIP")
        assertNotNull("FREESHIP should exist", restored2)
        assertEquals(CouponStatus.REDEEMED, restored2!!.status)
        assertEquals(CouponSource.MANUAL, restored2.source)
        assertEquals(1700000050000L, restored2.createdAt)
        assertEquals(1700000100000L, restored2.redeemedAt)
        assertTrue("Restored photo file should exist", File(restored2.imagePath).exists())
        assertEquals("image_bytes_2", File(restored2.imagePath).readText())
    }

    @Test
    fun testMergeDuplicateHandlingSkipsExistingCodes() = runTest {
        // Seed existing coupon in database
        val existingPhoto = createPhotoFile("existing.jpg", "existing_data")
        couponDao.insert(
            Coupon(
                code = "DUPLICATE_CODE",
                imagePath = existingPhoto.absolutePath,
                status = CouponStatus.UNSCANNED,
                source = CouponSource.QR,
                createdAt = 1000L
            )
        )
        assertEquals(1, couponDao.getAllCouponsList().size)

        // Prepare backup with 2 coupons: one duplicate, one new
        val zipBytesOut = ByteArrayOutputStream()
        ZipOutputStream(zipBytesOut).use { zip ->
            // manifest
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """
                {
                  "appVersion": "1.0",
                  "dbSchemaVersion": 4,
                  "exportedAt": 1700000000000,
                  "couponCount": 2,
                  "photoCount": 2
                }
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            // coupons.json
            zip.putNextEntry(ZipEntry("coupons.json"))
            zip.write(
                """
                [
                  {
                    "code": "duplicate_code",
                    "imagePath": "dup.jpg",
                    "status": "REDEEMED",
                    "source": "MANUAL",
                    "createdAt": 2000,
                    "extractionState": "DONE"
                  },
                  {
                    "code": "BRAND_NEW_CODE",
                    "imagePath": "new.jpg",
                    "status": "UNSCANNED",
                    "source": "QR",
                    "createdAt": 3000,
                    "extractionState": "DONE"
                  }
                ]
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            // originals
            zip.putNextEntry(ZipEntry("originals/dup.jpg"))
            zip.write("dup_content".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("originals/new.jpg"))
            zip.write("new_content".toByteArray())
            zip.closeEntry()
        }

        val zipBytes = zipBytesOut.toByteArray()

        // Restore using MERGE mode
        val result = DataBackupManager.restoreBackup(
            context = context,
            inputStream = ByteArrayInputStream(zipBytes),
            mode = RestoreMode.MERGE,
            database = db,
            preferences = preferences
        )

        assertTrue(result.isSuccess)
        val restoreResult = result.getOrThrow()
        assertEquals(1, restoreResult.importedCount)
        assertEquals(1, restoreResult.skippedDuplicates)

        val all = couponDao.getAllCouponsList()
        assertEquals(2, all.size)

        // Verify existing coupon was not overwritten
        val existing = couponDao.getCouponByCode("DUPLICATE_CODE")
        assertNotNull(existing)
        assertEquals(1000L, existing!!.createdAt)
        assertEquals(CouponStatus.UNSCANNED, existing.status) // original status kept

        // Verify new coupon was added
        val newCoupon = couponDao.getCouponByCode("BRAND_NEW_CODE")
        assertNotNull(newCoupon)
        assertEquals(3000L, newCoupon!!.createdAt)
    }

    @Test
    fun testMaliciousZipWithZipSlipPathTraversalIsRejected() = runTest {
        // Create zip with malicious path traversal "../evil.txt"
        val zipBytesOut = ByteArrayOutputStream()
        ZipOutputStream(zipBytesOut).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"appVersion":"1.0","dbSchemaVersion":4}""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("malicious payload".toByteArray())
            zip.closeEntry()
        }

        val zipBytes = zipBytesOut.toByteArray()

        // Validation should reject it
        val validationResult = DataBackupManager.validateBackup(ByteArrayInputStream(zipBytes))
        assertTrue("Validation must fail for Zip-Slip attack", validationResult.isFailure)
        val errorMsg = validationResult.exceptionOrNull()?.message ?: ""
        assertTrue("Error message should mention zip-slip or unsafe path: $errorMsg",
            errorMsg.contains("Zip-slip", ignoreCase = true) || errorMsg.contains("unsafe", ignoreCase = true)
        )

        // Restore should also reject it
        val restoreResult = DataBackupManager.restoreBackup(
            context = context,
            inputStream = ByteArrayInputStream(zipBytes),
            mode = RestoreMode.MERGE,
            database = db,
            preferences = preferences
        )
        assertTrue("Restore must fail for Zip-Slip attack", restoreResult.isFailure)
    }

    @Test
    fun testDeleteAllRedeemedDeletesRowsAndOrphanPhotosOnly() = runTest {
        val photo1 = createPhotoFile("photo_unscanned.jpg", "keep_unscanned")
        val photo2 = createPhotoFile("photo_redeemed_1.jpg", "delete_redeemed")
        val photo3 = createPhotoFile("photo_shared.jpg", "keep_shared")

        val c1 = Coupon(
            code = "UNSCANNED_1",
            imagePath = photo1.absolutePath,
            status = CouponStatus.UNSCANNED,
            source = CouponSource.QR
        )
        val c2 = Coupon(
            code = "REDEEMED_1",
            imagePath = photo2.absolutePath,
            status = CouponStatus.REDEEMED,
            source = CouponSource.MANUAL
        )
        val c3 = Coupon(
            code = "REDEEMED_2",
            imagePath = photo3.absolutePath,
            status = CouponStatus.REDEEMED,
            source = CouponSource.QR
        )
        val c4 = Coupon(
            code = "UNSCANNED_2",
            imagePath = photo3.absolutePath, // Shares photo3 with c3!
            status = CouponStatus.UNSCANNED,
            source = CouponSource.QR
        )

        couponDao.insert(c1)
        couponDao.insert(c2)
        couponDao.insert(c3)
        couponDao.insert(c4)

        assertEquals(4, couponDao.getAllCouponsList().size)
        assertEquals(2, couponDao.getRedeemedCouponsList().size)

        // Execute deleteAllRedeemed
        val deletedCount = repository.deleteAllRedeemed()
        assertEquals(2, deletedCount)

        val remaining = couponDao.getAllCouponsList()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.status == CouponStatus.UNSCANNED })

        // photo1 was for UNSCANNED_1 -> must still exist
        assertTrue(photo1.exists())
        // photo2 was exclusively for REDEEMED_1 -> must be deleted
        assertFalse(photo2.exists())
        // photo3 was shared with UNSCANNED_2 -> must still exist!
        assertTrue(photo3.exists())
    }
}
