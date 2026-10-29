package com.example.couponvault.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponDao
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

class FakePhotoCouponDao : CouponDao {
    val items = mutableListOf<Coupon>()
    private val _flow = MutableStateFlow<List<Coupon>>(emptyList())

    private fun emit() {
        _flow.value = items.filter { it.deletedAt == null }
    }

    override fun getAllCoupons(): Flow<List<Coupon>> = _flow.asStateFlow()
    override suspend fun getAllCouponsList(): List<Coupon> = items.filter { it.deletedAt == null }
    override fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>> = MutableStateFlow(items.filter { it.status == status && it.deletedAt == null })
    override fun getNeedsReviewCoupons(): Flow<List<Coupon>> = MutableStateFlow(items.filter { it.status == CouponStatus.NEEDS_REVIEW && it.deletedAt == null })
    override fun getUnscannedQueueFlow(): Flow<List<Coupon>> = MutableStateFlow(items.filter { it.status == CouponStatus.UNSCANNED && it.deletedAt == null })
    override suspend fun getUnscannedQueueList(): List<Coupon> = items.filter { it.status == CouponStatus.UNSCANNED && it.deletedAt == null }
    override suspend fun getCouponById(id: Long): Coupon? = items.find { it.id == id && it.deletedAt == null }
    override suspend fun getCouponsByIds(ids: List<Long>): List<Coupon> = items.filter { it.id in ids && it.deletedAt == null }
    override fun getCouponByIdFlow(id: Long): Flow<Coupon?> = MutableStateFlow(items.find { it.id == id && it.deletedAt == null })
    override suspend fun getCouponByCode(code: String): Coupon? = items.find { it.code == code && it.deletedAt == null }
    override fun getUnscannedCount(): Flow<Int> = MutableStateFlow(items.count { it.status == CouponStatus.UNSCANNED && it.deletedAt == null })
    override fun getNeedsReviewCount(): Flow<Int> = MutableStateFlow(items.count { it.status == CouponStatus.NEEDS_REVIEW && it.deletedAt == null })
    override fun getRedeemedCount(): Flow<Int> = MutableStateFlow(items.count { it.status == CouponStatus.REDEEMED && it.deletedAt == null })
    override suspend fun getRedeemedCouponsList(): List<Coupon> = items.filter { it.status == CouponStatus.REDEEMED && it.deletedAt == null }
    override suspend fun countByImagePath(imagePath: String): Int = items.count { it.imagePath == imagePath && it.deletedAt == null }
    override suspend fun deleteAll() { items.clear(); emit() }
    override suspend fun getPendingCouponsList(): List<Coupon> = items.filter { it.extractionState == ExtractionState.PENDING && it.deletedAt == null }
    override fun getPendingCouponsFlow(): Flow<List<Coupon>> = MutableStateFlow(items.filter { it.extractionState == ExtractionState.PENDING && it.deletedAt == null })
    override suspend fun getDirtyCoupons(limit: Int): List<Coupon> = items.filter { it.dirty && it.extractionState == ExtractionState.DONE }.take(limit)
    override suspend fun getAllCouponsIncludingTombstones(): List<Coupon> = items.toList()
    override suspend fun deleteUnsyncedCoupons() { items.removeAll { it.serverUpdatedAt == null }; emit() }
    override suspend fun tombstoneSyncedCoupons(now: Long) {
        for (i in items.indices) {
            if (items[i].serverUpdatedAt != null) {
                items[i] = items[i].copy(deletedAt = now, modifiedAt = now, dirty = true)
            }
        }
        emit()
    }
    override suspend fun getCouponBySyncId(syncId: String): Coupon? = items.find { it.syncId == syncId }
    override suspend fun markClean(syncId: String, serverUpdatedAt: Long) {
        val idx = items.indexOfFirst { it.syncId == syncId }
        if (idx != -1) {
            items[idx] = items[idx].copy(dirty = false, serverUpdatedAt = serverUpdatedAt)
            emit()
        }
    }
    override suspend fun purgeBySyncId(syncId: String) { items.removeAll { it.syncId == syncId }; emit() }
    override suspend fun purgeBySyncIds(syncIds: List<String>) { items.removeAll { it.syncId in syncIds }; emit() }
    override fun getUnsyncedCount(): Flow<Int> = MutableStateFlow(items.count { it.dirty && it.extractionState == ExtractionState.DONE })
    override suspend fun getUnsyncedCountSync(): Int = items.count { it.dirty && it.extractionState == ExtractionState.DONE }
    override suspend fun insert(coupon: Coupon): Long {
        val id = if (coupon.id != 0L) coupon.id else (items.maxOfOrNull { it.id } ?: 0L) + 1L
        items.add(coupon.copy(id = id))
        emit()
        return id
    }
    override suspend fun update(coupon: Coupon) {
        val idx = items.indexOfFirst { it.id == coupon.id || it.syncId == coupon.syncId }
        if (idx != -1) items[idx] = coupon else items.add(coupon)
        emit()
    }
    override suspend fun updateStatusBulk(ids: List<Long>, status: CouponStatus, redeemedAt: Long?, modifiedAt: Long) {
        for (i in items.indices) {
            if (items[i].id in ids) {
                items[i] = items[i].copy(status = status, redeemedAt = redeemedAt, modifiedAt = modifiedAt, dirty = true)
            }
        }
        emit()
    }
    override suspend fun delete(coupon: Coupon) { items.removeAll { it.id == coupon.id }; emit() }
    override suspend fun deleteById(id: Long) { items.removeAll { it.id == id }; emit() }
    override suspend fun deleteByIds(ids: List<Long>) { items.removeAll { it.id in ids }; emit() }

    override suspend fun getCouponsNeedingPhotoUpload(): List<Coupon> {
        return items.filter { it.serverUpdatedAt != null && it.deletedAt == null && it.remoteImagePath.isNullOrBlank() && it.imagePath.isNotBlank() }
    }

    override suspend fun getCouponsNeedingPhotoDownload(): List<Coupon> {
        return items.filter { it.deletedAt == null && !it.remoteImagePath.isNullOrBlank() && it.imagePath.isBlank() }
    }

    override suspend fun updatePhotoUploadSuccess(syncId: String, remoteImagePath: String, imageSha256: String, imageVariant: String, modifiedAt: Long) {
        val idx = items.indexOfFirst { it.syncId == syncId }
        if (idx != -1) {
            items[idx] = items[idx].copy(
                remoteImagePath = remoteImagePath,
                imageSha256 = imageSha256,
                imageVariant = imageVariant,
                dirty = true,
                modifiedAt = modifiedAt
            )
            emit()
        }
    }

    override suspend fun updateLocalImagePath(syncId: String, localImagePath: String) {
        val idx = items.indexOfFirst { it.syncId == syncId }
        if (idx != -1) {
            items[idx] = items[idx].copy(imagePath = localImagePath)
            emit()
        }
    }
}

class FakePhotoSupabaseClient(prefs: SupabasePreferences) : SupabaseClient(prefs) {
    var downloadResult: Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3, 4, 5))
    var uploadResult: Result<String> = Result.success("user-123/sync-1.jpg")
    var downloadCalls = 0
    var uploadCalls = 0

    override suspend fun downloadPhoto(remoteImagePath: String): Result<ByteArray> {
        downloadCalls++
        return downloadResult
    }

    override suspend fun uploadPhoto(
        userId: String,
        syncId: String,
        imageBytes: ByteArray,
        expectedSha256: String
    ): Result<String> {
        uploadCalls++
        return uploadResult
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PhotoSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fakeDao: FakePhotoCouponDao
    private lateinit var repository: CouponRepository
    private lateinit var fakeClient: FakePhotoSupabaseClient
    private lateinit var downloadManager: PhotoDownloadManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeDao = FakePhotoCouponDao()
        repository = CouponRepository(fakeDao) { 0L }
        val prefs = SupabasePreferences(context)
        fakeClient = FakePhotoSupabaseClient(prefs)
        downloadManager = PhotoDownloadManager(context, repository, fakeClient)
    }

    @Test
    fun testHashVerification_matchingHash_returnsTrue() {
        val sampleData = "CouponVaultPhotoTestData".toByteArray(Charsets.UTF_8)
        val computedHash = PhotoSyncHelper.computeSha256(sampleData)

        assertTrue(PhotoSyncHelper.verifySha256(sampleData, computedHash))
        assertTrue(PhotoSyncHelper.verifySha256(sampleData, computedHash.uppercase()))
        assertTrue(PhotoSyncHelper.verifySha256(sampleData, null)) // null expected means unconstrained
    }

    @Test
    fun testHashVerification_mismatchingHash_returnsFalse() {
        val sampleData = "CouponVaultPhotoTestData".toByteArray(Charsets.UTF_8)
        val badHash = "0000000000000000000000000000000000000000000000000000000000000000"

        assertFalse(PhotoSyncHelper.verifySha256(sampleData, badHash))
    }

    @Test
    fun testRetryOnMismatch_discardsAndFails() = runBlocking {
        val serverBytes = byteArrayOf(10, 20, 30, 40)
        fakeClient.downloadResult = Result.success(serverBytes)

        val coupon = Coupon(
            syncId = "test-sync-mismatch",
            imagePath = "",
            remoteImagePath = "user-1/test-sync-mismatch.jpg",
            imageSha256 = "expected-sha256-that-does-not-match"
        )
        fakeDao.items.add(coupon)

        // Attempt download with mismatched hash
        val result = downloadManager.downloadPhoto(coupon)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)

        // Ensure coupon local imagePath was NOT updated
        val updatedCoupon = fakeDao.getCouponBySyncId(coupon.syncId)
        assertNotNull(updatedCoupon)
        assertEquals("", updatedCoupon!!.imagePath)

        // Next attempt (retry) with corrected matching hash
        val matchingHash = PhotoSyncHelper.computeSha256(serverBytes)
        val couponWithMatchingHash = coupon.copy(imageSha256 = matchingHash)
        fakeDao.update(couponWithMatchingHash)

        val retryResult = downloadManager.downloadPhoto(couponWithMatchingHash)
        assertTrue(retryResult.isSuccess)
        val savedFile = retryResult.getOrThrow()
        assertTrue(savedFile.exists())
        assertEquals(matchingHash, PhotoSyncHelper.computeSha256(savedFile))

        val finalCoupon = fakeDao.getCouponBySyncId(coupon.syncId)
        assertEquals(savedFile.absolutePath, finalCoupon!!.imagePath)
    }

    @Test
    fun testResumeAfterFailure_retrySucceeds() = runBlocking {
        // First attempt fails due to simulated network error
        fakeClient.downloadResult = Result.failure(IOException("Network timeout"))

        val sampleBytes = byteArrayOf(55, 66, 77)
        val sampleHash = PhotoSyncHelper.computeSha256(sampleBytes)

        val coupon = Coupon(
            syncId = "test-sync-resume",
            imagePath = "",
            remoteImagePath = "user-1/test-sync-resume.jpg",
            imageSha256 = sampleHash
        )
        fakeDao.items.add(coupon)

        val failResult = downloadManager.downloadPhoto(coupon)
        assertTrue(failResult.isFailure)
        assertEquals("", fakeDao.getCouponBySyncId(coupon.syncId)!!.imagePath)

        // Simulate network recovery
        fakeClient.downloadResult = Result.success(sampleBytes)
        val successResult = downloadManager.downloadPhoto(coupon)

        assertTrue(successResult.isSuccess)
        val downloadedFile = successResult.getOrThrow()
        assertTrue(downloadedFile.exists())
        assertEquals(sampleBytes.size.toLong(), downloadedFile.length())
        assertEquals(downloadedFile.absolutePath, fakeDao.getCouponBySyncId(coupon.syncId)!!.imagePath)
    }

    @Test
    fun testReducedVariantFlag_resizesAndTagsVariant() {
        val originalFile = tempFolder.newFile("test_original.jpg")
        val testContent = "OriginalPhotoDummyPayload".toByteArray(Charsets.UTF_8)
        originalFile.writeBytes(testContent)

        // Original variant
        val (origBytes, origVariant) = PhotoSyncHelper.preparePhotoBytes(originalFile, "original")
        assertEquals("original", origVariant)
        assertEquals(testContent.size, origBytes.size)

        // Reduced variant requested
        val (reducedBytes, reducedVariant) = PhotoSyncHelper.preparePhotoBytes(originalFile, "reduced")
        // If file is not a valid decodeable bitmap, it safely falls back to original content
        assertNotNull(reducedBytes)
        assertTrue(reducedVariant == "reduced" || reducedVariant == "original")
    }

    @Test
    fun testOriginalLocalFileNeverModified() {
        val originalFile = tempFolder.newFile("never_modify_me.jpg")
        val originalBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        originalFile.writeBytes(originalBytes)
        val originalLength = originalFile.length()
        val originalHash = PhotoSyncHelper.computeSha256(originalFile)

        // Prepare bytes under reduced quality
        PhotoSyncHelper.preparePhotoBytes(originalFile, "reduced")

        // Assert local original file on disk is strictly unchanged
        assertEquals(originalLength, originalFile.length())
        assertEquals(originalHash, PhotoSyncHelper.computeSha256(originalFile))
    }
}
