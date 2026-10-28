package com.example.couponvault.data

import com.example.couponvault.util.ClipboardHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeClipboardHelper : ClipboardHelper {
    var lastCopied: String? = null
    var wasSensitive: Boolean = false

    override fun copyText(text: String, isSensitive: Boolean) {
        lastCopied = text
        wasSensitive = isSensitive
    }
}

class FakeOverlayPreferences : OverlayPreferences() {
    val currentCouponId = MutableStateFlow<Long?>(null)
    val previousCouponId = MutableStateFlow<Long?>(null)
    val autoMarkRedeemed = MutableStateFlow(true)
    val overlayEnabled = MutableStateFlow(false)

    override val currentCouponIdFlow: Flow<Long?> = currentCouponId.asStateFlow()
    override val previousCouponIdFlow: Flow<Long?> = previousCouponId.asStateFlow()
    override val autoMarkRedeemedFlow: Flow<Boolean> = autoMarkRedeemed.asStateFlow()
    override val overlayEnabledFlow: Flow<Boolean> = overlayEnabled.asStateFlow()

    override suspend fun setCurrentCouponId(id: Long?) {
        currentCouponId.value = id
    }

    override suspend fun setPreviousCouponId(id: Long?) {
        previousCouponId.value = id
    }

    override suspend fun setAutoMarkRedeemed(enabled: Boolean) {
        autoMarkRedeemed.value = enabled
    }

    override suspend fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled.value = enabled
    }
}

class FakeCouponDao : CouponDao {
    private val coupons = mutableListOf<Coupon>()
    private val couponsFlow = MutableStateFlow<List<Coupon>>(emptyList())

    fun seed(items: List<Coupon>) {
        coupons.clear()
        coupons.addAll(items)
        emit()
    }

    private fun emit() {
        couponsFlow.value = coupons.toList()
    }

    override fun getAllCoupons(): Flow<List<Coupon>> = couponsFlow.asStateFlow()

    override suspend fun getAllCouponsList(): List<Coupon> = coupons.toList()

    override fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>> =
        couponsFlow.map { list -> list.filter { it.status == status } }

    override fun getNeedsReviewCoupons(): Flow<List<Coupon>> =
        couponsFlow.map { list -> list.filter { it.status == CouponStatus.NEEDS_REVIEW && it.extractionState == ExtractionState.DONE } }

    override suspend fun getPendingCouponsList(): List<Coupon> =
        coupons.filter { it.extractionState == ExtractionState.PENDING }

    override fun getPendingCouponsFlow(): Flow<List<Coupon>> =
        couponsFlow.map { list -> list.filter { it.extractionState == ExtractionState.PENDING } }

    override fun getUnscannedQueueFlow(): Flow<List<Coupon>> =
        couponsFlow.map { list ->
            list.filter { it.status == CouponStatus.UNSCANNED && !it.code.isNullOrBlank() }
                .sortedBy { it.createdAt }
        }

    override suspend fun getUnscannedQueueList(): List<Coupon> =
        coupons.filter { it.status == CouponStatus.UNSCANNED && !it.code.isNullOrBlank() }
            .sortedBy { it.createdAt }

    override suspend fun getCouponById(id: Long): Coupon? =
        coupons.find { it.id == id }

    override suspend fun getCouponsByIds(ids: List<Long>): List<Coupon> =
        coupons.filter { it.id in ids }

    override fun getCouponByIdFlow(id: Long): Flow<Coupon?> =
        couponsFlow.map { list -> list.find { it.id == id } }

    override suspend fun getCouponByCode(code: String): Coupon? =
        coupons.find { it.code.equals(code, ignoreCase = true) }

    override fun getUnscannedCount(): Flow<Int> =
        couponsFlow.map { list -> list.count { it.status == CouponStatus.UNSCANNED } }

    override fun getNeedsReviewCount(): Flow<Int> =
        couponsFlow.map { list -> list.count { it.status == CouponStatus.NEEDS_REVIEW && it.extractionState == ExtractionState.DONE } }

    override fun getRedeemedCount(): Flow<Int> =
        couponsFlow.map { list -> list.count { it.status == CouponStatus.REDEEMED } }

    override suspend fun getRedeemedCouponsList(): List<Coupon> =
        coupons.filter { it.status == CouponStatus.REDEEMED }

    override suspend fun countByImagePath(imagePath: String): Int =
        coupons.count { it.imagePath == imagePath }

    override suspend fun deleteAll() {
        coupons.clear()
        emit()
    }

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
        coupons.find { it.syncId == syncId }

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

    override suspend fun delete(coupon: Coupon) {
        coupons.removeAll { it.id == coupon.id }
        emit()
    }

    override suspend fun deleteById(id: Long) {
        coupons.removeAll { it.id == id }
        emit()
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        coupons.removeAll { it.id in ids }
        emit()
    }
}

class CouponQueueManagerTest {

    private lateinit var dao: FakeCouponDao
    private lateinit var prefs: FakeOverlayPreferences
    private lateinit var clipboard: FakeClipboardHelper

    @Before
    fun setup() {
        dao = FakeCouponDao()
        prefs = FakeOverlayPreferences()
        clipboard = FakeClipboardHelper()
    }

    @Test
    fun testCopyNextTransitionsAndAutoMarksRedeemed() = runTest {
        val c1 = Coupon(id = 1, code = "CODE1111", imagePath = "/p1", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 1000)
        val c2 = Coupon(id = 2, code = "CODE2222", imagePath = "/p2", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 2000)
        val c3 = Coupon(id = 3, code = "CODE3333", imagePath = "/p3", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 3000)
        dao.seed(listOf(c1, c2, c3))

        // Create manager using preferences mock
        val manager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard
        )

        // First copyNext: copies C1, C1 becomes current
        val code1 = manager.copyNext()
        assertEquals("CODE1111", code1)
        assertEquals("CODE1111", clipboard.lastCopied)
        assertTrue(clipboard.wasSensitive)
        // C1 is still unscanned until next copy
        assertEquals(CouponStatus.UNSCANNED, dao.getCouponById(1)?.status)

        // Second copyNext: auto-marks C1 as redeemed, copies C2
        val code2 = manager.copyNext()
        assertEquals("CODE2222", code2)
        assertEquals("CODE2222", clipboard.lastCopied)
        val updatedC1 = dao.getCouponById(1)
        assertEquals(CouponStatus.REDEEMED, updatedC1?.status)
        assertNotNull(updatedC1?.redeemedAt)

        // Third copyNext: auto-marks C2 as redeemed, copies C3
        val code3 = manager.copyNext()
        assertEquals("CODE3333", code3)
        assertEquals(CouponStatus.REDEEMED, dao.getCouponById(2)?.status)

        // Fourth copyNext: auto-marks C3 as redeemed, queue is now empty
        val code4 = manager.copyNext()
        assertNull(code4)
        assertEquals(CouponStatus.REDEEMED, dao.getCouponById(3)?.status)
    }

    @Test
    fun testRepeatReCopiesCurrentCode() = runTest {
        val c1 = Coupon(id = 1, code = "CODE1111", imagePath = "/p1", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 1000)
        dao.seed(listOf(c1))

        val manager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard
        )

        manager.copyNext()
        assertEquals("CODE1111", clipboard.lastCopied)

        clipboard.lastCopied = "SOMETHING_ELSE"
        val repeated = manager.repeat()
        assertEquals("CODE1111", repeated)
        assertEquals("CODE1111", clipboard.lastCopied)
        assertEquals(CouponStatus.UNSCANNED, dao.getCouponById(1)?.status)
    }

    @Test
    fun testMarkCurrentFailedAdvancesWithoutRedeeming() = runTest {
        val c1 = Coupon(id = 1, code = "CODE1111", imagePath = "/p1", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 1000)
        val c2 = Coupon(id = 2, code = "CODE2222", imagePath = "/p2", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 2000)
        dao.seed(listOf(c1, c2))

        val manager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard
        )

        manager.copyNext()
        assertEquals("CODE1111", clipboard.lastCopied)

        val nextCode = manager.markCurrentFailed()
        assertEquals("CODE2222", nextCode)
        assertEquals(CouponStatus.FAILED, dao.getCouponById(1)?.status)
        assertNull(dao.getCouponById(1)?.redeemedAt)
        assertEquals("CODE2222", clipboard.lastCopied)
    }

    @Test
    fun testUndoRestoresPreviousCoupon() = runTest {
        val c1 = Coupon(id = 1, code = "CODE1111", imagePath = "/p1", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 1000)
        val c2 = Coupon(id = 2, code = "CODE2222", imagePath = "/p2", status = CouponStatus.UNSCANNED, source = CouponSource.QR, createdAt = 2000)
        dao.seed(listOf(c1, c2))

        val manager = CouponQueueManager(
            couponDao = dao,
            preferences = prefs,
            clipboardHelper = clipboard
        )

        manager.copyNext() // copies C1
        manager.copyNext() // marks C1 redeemed, copies C2
        assertEquals(CouponStatus.REDEEMED, dao.getCouponById(1)?.status)

        // Undo should restore C1 to UNSCANNED and copy its code
        val undoneCode = manager.undo()
        assertEquals("CODE1111", undoneCode)
        assertEquals("CODE1111", clipboard.lastCopied)
        assertEquals(CouponStatus.UNSCANNED, dao.getCouponById(1)?.status)
        assertNull(dao.getCouponById(1)?.redeemedAt)
    }
}
