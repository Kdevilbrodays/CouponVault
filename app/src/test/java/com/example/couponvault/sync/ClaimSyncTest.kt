package com.example.couponvault.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponDao
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import com.example.couponvault.data.OverlayPreferences
import com.example.couponvault.util.ClipboardHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.IOException

class TestClipboardHelper : ClipboardHelper {
    var lastCopied: String? = null
    var wasSensitive: Boolean = false

    override fun copyText(text: String, isSensitive: Boolean) {
        lastCopied = text
        wasSensitive = isSensitive
    }
}

class TestOverlayPreferences : OverlayPreferences() {
    val currentCouponId = MutableStateFlow<Long?>(null)
    val previousCouponId = MutableStateFlow<Long?>(null)
    val autoMarkRedeemed = MutableStateFlow(true)
    var deviceId = "device-test-uuid-123"

    override val currentCouponIdFlow: Flow<Long?> = currentCouponId.asStateFlow()
    override val previousCouponIdFlow: Flow<Long?> = previousCouponId.asStateFlow()
    override val autoMarkRedeemedFlow: Flow<Boolean> = autoMarkRedeemed.asStateFlow()

    override suspend fun setCurrentCouponId(id: Long?) {
        currentCouponId.value = id
    }

    override suspend fun setPreviousCouponId(id: Long?) {
        previousCouponId.value = id
    }

    override suspend fun setAutoMarkRedeemed(enabled: Boolean) {
        autoMarkRedeemed.value = enabled
    }

    override suspend fun getOrCreateDeviceId(): String = deviceId
}

class TestCouponDao : CouponDao {
    val coupons = mutableListOf<Coupon>()
    private val couponsFlow = MutableStateFlow<List<Coupon>>(emptyList())

    fun seed(items: List<Coupon>) {
        coupons.clear()
        coupons.addAll(items)
        emit()
    }

    private fun emit() {
        couponsFlow.value = coupons.filter { it.deletedAt == null }.toList()
    }

    override fun getAllCoupons(): Flow<List<Coupon>> = couponsFlow.asStateFlow()
    override suspend fun getAllCouponsList(): List<Coupon> = coupons.filter { it.deletedAt == null }
    override fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>> =
        couponsFlow.map { list -> list.filter { it.status == status } }
    override fun getNeedsReviewCoupons(): Flow<List<Coupon>> =
        couponsFlow.map { list -> list.filter { it.status == CouponStatus.NEEDS_REVIEW } }
    override suspend fun getPendingCouponsList(): List<Coupon> = emptyList()
    override fun getPendingCouponsFlow(): Flow<List<Coupon>> = MutableStateFlow(emptyList())

    override fun getUnscannedQueueFlow(): Flow<List<Coupon>> =
        couponsFlow.map { list ->
            list.filter { it.status == CouponStatus.UNSCANNED && !it.code.isNullOrBlank() }
                .sortedBy { it.createdAt }
        }

    override suspend fun getUnscannedQueueList(): List<Coupon> =
        coupons.filter { it.status == CouponStatus.UNSCANNED && !it.code.isNullOrBlank() && it.deletedAt == null }
            .sortedBy { it.createdAt }

    override suspend fun getCouponById(id: Long): Coupon? =
        coupons.find { it.id == id && it.deletedAt == null }

    override suspend fun getCouponsByIds(ids: List<Long>): List<Coupon> =
        coupons.filter { it.id in ids && it.deletedAt == null }

    override fun getCouponByIdFlow(id: Long): Flow<Coupon?> =
        couponsFlow.map { list -> list.find { it.id == id } }

    override suspend fun getCouponByCode(code: String): Coupon? =
        coupons.find { it.code.equals(code, ignoreCase = true) && it.deletedAt == null }

    override fun getUnscannedCount(): Flow<Int> =
        couponsFlow.map { list -> list.count { it.status == CouponStatus.UNSCANNED } }

    override fun getNeedsReviewCount(): Flow<Int> =
        couponsFlow.map { list -> list.count { it.status == CouponStatus.NEEDS_REVIEW } }

    override fun getRedeemedCount(): Flow<Int> =
        couponsFlow.map { list -> list.count { it.status == CouponStatus.REDEEMED } }

    override suspend fun getRedeemedCouponsList(): List<Coupon> =
        coupons.filter { it.status == CouponStatus.REDEEMED && it.deletedAt == null }

    override suspend fun countByImagePath(imagePath: String): Int = 0
    override suspend fun deleteAll() { coupons.clear(); emit() }

    override suspend fun insert(coupon: Coupon): Long {
        val id = if (coupon.id > 0) coupon.id else (coupons.maxOfOrNull { it.id } ?: 0L) + 1L
        val saved = coupon.copy(id = id)
        coupons.add(saved)
        emit()
        return id
    }

    override suspend fun update(coupon: Coupon) {
        val index = coupons.indexOfFirst { it.id == coupon.id }
        if (index >= 0) {
            coupons[index] = coupon
            emit()
        }
    }

    override suspend fun getDirtyCoupons(limit: Int): List<Coupon> =
        coupons.filter { it.dirty }.take(limit)

    override suspend fun getCouponBySyncId(syncId: String): Coupon? =
        coupons.find { it.syncId == syncId && it.deletedAt == null }

    override suspend fun markClean(syncId: String, serverUpdatedAt: Long) {
        val index = coupons.indexOfFirst { it.syncId == syncId }
        if (index >= 0) {
            coupons[index] = coupons[index].copy(dirty = false, serverUpdatedAt = serverUpdatedAt)
            emit()
        }
    }

    override suspend fun purgeBySyncId(syncId: String) {
        coupons.removeAll { it.syncId == syncId }
        emit()
    }

    override suspend fun purgeBySyncIds(syncIds: List<String>) {
        coupons.removeAll { it.syncId in syncIds }
        emit()
    }

    override fun getUnsyncedCount(): Flow<Int> =
        couponsFlow.map { list -> list.count { it.dirty } }

    override suspend fun getUnsyncedCountSync(): Int =
        coupons.count { it.dirty }

    override suspend fun updateStatusBulk(ids: List<Long>, status: CouponStatus, redeemedAt: Long?, modifiedAt: Long) {
        for (i in coupons.indices) {
            if (coupons[i].id in ids) {
                coupons[i] = coupons[i].copy(status = status, redeemedAt = redeemedAt, modifiedAt = modifiedAt, dirty = true)
            }
        }
        emit()
    }

    override suspend fun delete(coupon: Coupon) { coupons.removeAll { it.id == coupon.id }; emit() }
    override suspend fun deleteById(id: Long) { coupons.removeAll { it.id == id }; emit() }
    override suspend fun deleteByIds(ids: List<Long>) { coupons.removeAll { it.id in ids }; emit() }

    override suspend fun getCouponsNeedingPhotoUpload(): List<Coupon> {
        return coupons.filter { it.serverUpdatedAt != null && it.deletedAt == null && it.remoteImagePath.isNullOrBlank() && it.imagePath.isNotBlank() }
    }

    override suspend fun getCouponsNeedingPhotoDownload(): List<Coupon> {
        return coupons.filter { it.deletedAt == null && !it.remoteImagePath.isNullOrBlank() && it.imagePath.isBlank() }
    }

    override suspend fun updatePhotoUploadSuccess(syncId: String, remoteImagePath: String, imageSha256: String, imageVariant: String, modifiedAt: Long) {
        val idx = coupons.indexOfFirst { it.syncId == syncId }
        if (idx != -1) {
            coupons[idx] = coupons[idx].copy(
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
        val idx = coupons.indexOfFirst { it.syncId == syncId }
        if (idx != -1) {
            coupons[idx] = coupons[idx].copy(imagePath = localImagePath)
            emit()
        }
    }

    override suspend fun getAllCouponsIncludingTombstones(): List<Coupon> = coupons.toList()
    override suspend fun deleteUnsyncedCoupons() {
        coupons.removeAll { it.serverUpdatedAt == null }
        emit()
    }
    override suspend fun tombstoneSyncedCoupons(now: Long) {
        for (i in coupons.indices) {
            if (coupons[i].serverUpdatedAt != null) {
                coupons[i] = coupons[i].copy(deletedAt = now, dirty = true, modifiedAt = now)
            }
        }
        emit()
    }
}

class FakeClaimSupabaseClient(prefs: SupabasePreferences) : SupabaseClient(prefs) {
    var claimNextHandler: (suspend (deviceId: String) -> Result<JSONObject?>)? = null
    var releaseHandler: (suspend (syncId: String) -> Result<Unit>)? = null
    val releasedSyncIds = mutableListOf<String>()

    override suspend fun claimNextCoupon(deviceId: String): Result<JSONObject?> {
        return claimNextHandler?.invoke(deviceId) ?: Result.success(null)
    }

    override suspend fun releaseCoupon(syncId: String): Result<Unit> {
        releasedSyncIds.add(syncId)
        return releaseHandler?.invoke(syncId) ?: Result.success(Unit)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClaimSyncTest {

    private lateinit var context: Context
    private lateinit var dao: TestCouponDao
    private lateinit var repository: CouponRepository
    private lateinit var prefs: TestOverlayPreferences
    private lateinit var clipboard: TestClipboardHelper
    private lateinit var supabasePrefs: SupabasePreferences
    private lateinit var client: FakeClaimSupabaseClient

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        dao = TestCouponDao()
        repository = CouponRepository(dao)
        prefs = TestOverlayPreferences()
        clipboard = TestClipboardHelper()
        supabasePrefs = SupabasePreferences(context)
        client = FakeClaimSupabaseClient(supabasePrefs)

        // Seed signed-in auth token in preferences
        supabasePrefs.saveAuthSession(
            AuthSession(
                accessToken = "valid.jwt.token",
                refreshToken = "valid.refresh.token",
                userEmail = "user@test.com",
                userId = "user-uuid-1"
            )
        )
    }

    @Test
    fun testClaimSuccessFlow() = runBlocking {
        // Remote returns an unscanned coupon
        client.claimNextHandler = { deviceId ->
            assertEquals("device-test-uuid-123", deviceId)
            val json = JSONObject().apply {
                put("id", "remote-uuid-101")
                put("code", "SAVE50NOW")
                put("status", "UNSCANNED")
                put("source", "QR")
                put("extraction_state", "DONE")
                put("created_at", System.currentTimeMillis() - 5000)
                put("modified_at", System.currentTimeMillis() - 5000)
                put("claimed_by", deviceId)
                put("claimed_at", System.currentTimeMillis())
            }
            Result.success(json)
        }

        val queueManager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard,
            supabaseClient = client,
            supabasePreferences = supabasePrefs,
            repository = repository,
            context = context,
            onlineChecker = { true }
        )

        val copiedCode = queueManager.copyNext()
        assertEquals("SAVE50NOW", copiedCode)
        assertEquals("SAVE50NOW", clipboard.lastCopied)
        assertFalse(queueManager.isLastCopyFallback.value)

        // Local DB now contains the coupon
        val localCoupon = dao.getCouponBySyncId("remote-uuid-101")
        assertNotNull(localCoupon)
        assertEquals("SAVE50NOW", localCoupon?.code)
        assertEquals(localCoupon?.id, prefs.currentCouponId.value)
    }

    @Test
    fun testClaimEmptySetShowsAllDone() = runBlocking {
        // Remote returns empty set (no coupons to claim)
        client.claimNextHandler = {
            Result.success(null)
        }

        val queueManager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard,
            supabaseClient = client,
            supabasePreferences = supabasePrefs,
            repository = repository,
            context = context,
            onlineChecker = { true }
        )

        val copiedCode = queueManager.copyNext()
        assertNull(copiedCode)
        assertFalse(queueManager.isLastCopyFallback.value)
        assertNull(prefs.currentCouponId.value)

        // queueState indicates allDone
        kotlinx.coroutines.delay(100)
        val state = queueManager.queueState.value
        assertTrue("Queue state should reflect all done", state.isAllDone)
    }

    @Test
    fun testClaimTimeoutSilentlyFallsBackToLocal() = runBlocking {
        // Local DB has a coupon
        val localCoupon = Coupon(
            id = 1L,
            syncId = "local-uuid-1",
            code = "FALLBACK-LOCAL-1",
            status = CouponStatus.UNSCANNED,
            source = CouponSource.MANUAL,
            createdAt = 1000L
        )
        dao.seed(listOf(localCoupon))

        // Remote simulates network delay > 2s
        client.claimNextHandler = {
            delay(2500L)
            Result.success(null)
        }

        val queueManager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard,
            supabaseClient = client,
            supabasePreferences = supabasePrefs,
            repository = repository,
            context = context,
            onlineChecker = { true }
        )

        val copiedCode = queueManager.copyNext()
        assertEquals("FALLBACK-LOCAL-1", copiedCode)
        assertEquals("FALLBACK-LOCAL-1", clipboard.lastCopied)
        assertTrue("Fallback indicator should be true after timeout", queueManager.isLastCopyFallback.value)
    }

    @Test
    fun testClaimFailureSilentlyFallsBackToLocal() = runBlocking {
        // Local DB has a coupon
        val localCoupon = Coupon(
            id = 2L,
            syncId = "local-uuid-2",
            code = "FALLBACK-LOCAL-2",
            status = CouponStatus.UNSCANNED,
            source = CouponSource.MANUAL,
            createdAt = 1000L
        )
        dao.seed(listOf(localCoupon))

        // Remote throws HTTP error / network failure
        client.claimNextHandler = {
            Result.failure(IOException("Server error 500"))
        }

        val queueManager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard,
            supabaseClient = client,
            supabasePreferences = supabasePrefs,
            repository = repository,
            context = context,
            onlineChecker = { true }
        )

        val copiedCode = queueManager.copyNext()
        assertEquals("FALLBACK-LOCAL-2", copiedCode)
        assertEquals("FALLBACK-LOCAL-2", clipboard.lastCopied)
        assertTrue("Fallback indicator should be true after network failure", queueManager.isLastCopyFallback.value)
    }

    @Test
    fun testUndoAndFailedTriggerFireAndForgetRelease() = runBlocking {
        val currentCoupon = Coupon(
            id = 10L,
            syncId = "sync-to-fail-10",
            code = "BADCODE",
            status = CouponStatus.UNSCANNED,
            source = CouponSource.MANUAL,
            createdAt = 1000L
        )
        val nextCoupon = Coupon(
            id = 11L,
            syncId = "sync-next-11",
            code = "NEXTCODE",
            status = CouponStatus.UNSCANNED,
            source = CouponSource.MANUAL,
            createdAt = 2000L
        )
        dao.seed(listOf(currentCoupon, nextCoupon))
        prefs.setCurrentCouponId(10L)

        val queueManager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard,
            supabaseClient = client,
            supabasePreferences = supabasePrefs,
            repository = repository,
            context = context,
            onlineChecker = { true }
        )

        // 1. Mark failed
        val nextCode = queueManager.markCurrentFailed()
        assertEquals("NEXTCODE", nextCode)
        // Give background coroutine time to fire
        delay(50)
        assertTrue(client.releasedSyncIds.contains("sync-to-fail-10"))

        // 2. Undo
        val restoredCode = queueManager.undo()
        assertEquals("BADCODE", restoredCode)
        delay(50)
        assertTrue(client.releasedSyncIds.contains("sync-to-fail-10"))
    }

    @Test
    fun testSignedOutKeepsNormalBehavior() = runBlocking {
        // Sign out
        supabasePrefs.clearAuthSession()

        val localCoupon = Coupon(
            id = 30L,
            syncId = "sync-30",
            code = "OFFLINECODE",
            status = CouponStatus.UNSCANNED,
            source = CouponSource.MANUAL,
            createdAt = 1000L
        )
        dao.seed(listOf(localCoupon))

        val queueManager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard,
            supabaseClient = client,
            supabasePreferences = supabasePrefs,
            repository = repository,
            context = context,
            onlineChecker = { true }
        )

        val code = queueManager.copyNext()
        assertEquals("OFFLINECODE", code)
        // Signed out does not mark fallback as true (UI unchanged)
        assertFalse(queueManager.isLastCopyFallback.value)
    }

    @Test
    fun testAutoMarkRedeemedTriggersPush() = runBlocking {
        val coupon1 = Coupon(
            id = 1L,
            syncId = "sync-1",
            code = "CODE1",
            status = CouponStatus.UNSCANNED,
            source = CouponSource.MANUAL,
            createdAt = 1000L
        )
        val coupon2 = Coupon(
            id = 2L,
            syncId = "sync-2",
            code = "CODE2",
            status = CouponStatus.UNSCANNED,
            source = CouponSource.MANUAL,
            createdAt = 2000L
        )
        dao.seed(listOf(coupon1, coupon2))
        prefs.setCurrentCouponId(1L)
        prefs.setAutoMarkRedeemed(true)

        var pushTriggerCount = 0

        val queueManager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard,
            supabaseClient = client,
            supabasePreferences = supabasePrefs,
            repository = repository,
            context = context,
            onlineChecker = { false }, // Offline fallback
            onImmediatePushNeeded = {
                pushTriggerCount++
            }
        )

        val code = queueManager.copyNext()
        assertEquals("CODE2", code)
        assertEquals(1, pushTriggerCount)

        val previous = dao.getCouponById(1L)
        assertNotNull(previous)
        assertEquals(CouponStatus.REDEEMED, previous?.status)
        assertTrue("Previous coupon should be dirty for sync push", previous?.dirty == true)
    }
}
