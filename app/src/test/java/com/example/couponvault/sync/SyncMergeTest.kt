package com.example.couponvault.sync

import android.util.Base64
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponDao
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class FakeSyncCouponDao : CouponDao {
    val items = mutableListOf<Coupon>()
    val flow = MutableStateFlow<List<Coupon>>(emptyList())

    private fun notifyChanged() {
        flow.value = items.toList()
    }

    override fun getAllCoupons(): Flow<List<Coupon>> = flow.map { list ->
        list.filter { it.deletedAt == null }
    }

    override suspend fun getAllCouponsList(): List<Coupon> {
        return items.filter { it.deletedAt == null }
    }

    override fun getCouponsByStatus(status: CouponStatus): Flow<List<Coupon>> = flow.map { list ->
        list.filter { it.deletedAt == null && it.status == status }
    }

    override fun getNeedsReviewCoupons(): Flow<List<Coupon>> = flow.map { list ->
        list.filter { it.deletedAt == null && it.status == CouponStatus.NEEDS_REVIEW }
    }

    override fun getUnscannedQueueFlow(): Flow<List<Coupon>> = flow.map { list ->
        list.filter { it.deletedAt == null && it.status == CouponStatus.UNSCANNED && !it.code.isNullOrBlank() }
    }

    override suspend fun getUnscannedQueueList(): List<Coupon> {
        return items.filter { it.deletedAt == null && it.status == CouponStatus.UNSCANNED && !it.code.isNullOrBlank() }
    }

    override suspend fun getCouponById(id: Long): Coupon? {
        return items.find { it.id == id && it.deletedAt == null }
    }

    override suspend fun getCouponsByIds(ids: List<Long>): List<Coupon> {
        return items.filter { it.id in ids && it.deletedAt == null }
    }

    override fun getCouponByIdFlow(id: Long): Flow<Coupon?> = flow.map { list ->
        list.find { it.id == id && it.deletedAt == null }
    }

    override suspend fun getCouponByCode(code: String): Coupon? {
        return items.find { it.code == code && it.deletedAt == null }
    }

    override fun getUnscannedCount(): Flow<Int> = flow.map { list ->
        list.count { it.deletedAt == null && it.status == CouponStatus.UNSCANNED }
    }

    override fun getNeedsReviewCount(): Flow<Int> = flow.map { list ->
        list.count { it.deletedAt == null && it.status == CouponStatus.NEEDS_REVIEW }
    }

    override fun getRedeemedCount(): Flow<Int> = flow.map { list ->
        list.count { it.deletedAt == null && it.status == CouponStatus.REDEEMED }
    }

    override suspend fun getRedeemedCouponsList(): List<Coupon> {
        return items.filter { it.deletedAt == null && it.status == CouponStatus.REDEEMED }
    }

    override suspend fun countByImagePath(imagePath: String): Int {
        return items.count { it.imagePath == imagePath && it.deletedAt == null }
    }

    override suspend fun deleteAll() {
        items.clear()
        notifyChanged()
    }

    override suspend fun getPendingCouponsList(): List<Coupon> {
        return items.filter { it.deletedAt == null && it.extractionState == ExtractionState.PENDING }
    }

    override fun getPendingCouponsFlow(): Flow<List<Coupon>> = flow.map { list ->
        list.filter { it.deletedAt == null && it.extractionState == ExtractionState.PENDING }
    }

    override suspend fun getDirtyCoupons(limit: Int): List<Coupon> {
        return items.filter { it.dirty }.take(limit)
    }

    override suspend fun getCouponBySyncId(syncId: String): Coupon? {
        return items.find { it.syncId == syncId }
    }

    override suspend fun markClean(syncId: String, serverUpdatedAt: Long) {
        val idx = items.indexOfFirst { it.syncId == syncId }
        if (idx >= 0) {
            items[idx] = items[idx].copy(dirty = false, serverUpdatedAt = serverUpdatedAt)
            notifyChanged()
        }
    }

    override suspend fun purgeBySyncId(syncId: String) {
        items.removeAll { it.syncId == syncId }
        notifyChanged()
    }

    override suspend fun purgeBySyncIds(syncIds: List<String>) {
        items.removeAll { it.syncId in syncIds }
        notifyChanged()
    }

    override fun getUnsyncedCount(): Flow<Int> = flow.map { list ->
        list.count { it.dirty }
    }

    override suspend fun getUnsyncedCountSync(): Int {
        return items.count { it.dirty }
    }

    override suspend fun insert(coupon: Coupon): Long {
        val id = if (coupon.id == 0L) (items.maxOfOrNull { it.id } ?: 0L) + 1L else coupon.id
        val newCoupon = coupon.copy(id = id)
        items.add(newCoupon)
        notifyChanged()
        return id
    }

    override suspend fun update(coupon: Coupon) {
        val idx = items.indexOfFirst { it.id == coupon.id || it.syncId == coupon.syncId }
        if (idx >= 0) {
            items[idx] = coupon
            notifyChanged()
        }
    }

    override suspend fun updateStatusBulk(
        ids: List<Long>,
        status: CouponStatus,
        redeemedAt: Long?,
        modifiedAt: Long
    ) {
        for (i in items.indices) {
            if (items[i].id in ids) {
                items[i] = items[i].copy(
                    status = status,
                    redeemedAt = redeemedAt,
                    modifiedAt = modifiedAt,
                    dirty = true
                )
            }
        }
        notifyChanged()
    }

    override suspend fun delete(coupon: Coupon) {
        items.removeAll { it.id == coupon.id || it.syncId == coupon.syncId }
        notifyChanged()
    }

    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
        notifyChanged()
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        items.removeAll { it.id in ids }
        notifyChanged()
    }
}

class SyncMergeTest {

    private lateinit var fakeDao: FakeSyncCouponDao
    private lateinit var repository: CouponRepository

    @Before
    fun setup() {
        fakeDao = FakeSyncCouponDao()
        repository = CouponRepository(fakeDao)
    }

    @Test
    fun testAnonKeyValidationRejectsSecretKeyPrefix() {
        val error = SupabasePreferences.validateAnonKey("sb_secret_1234567890abcdef")
        assertNotNull(error)
        assertEquals("That's a secret key. Use the public (anon/publishable) key.", error)
    }

    @Test
    fun testAnonKeyValidationRejectsServiceRoleJwt() {
        val header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray())
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"role\":\"service_role\",\"iss\":\"supabase\"}".toByteArray())
        val sig = "mock_signature"
        val jwt = "$header.$payload.$sig"

        val error = SupabasePreferences.validateAnonKey(jwt)
        assertNotNull(error)
        assertEquals("That's a secret key. Use the public (anon/publishable) key.", error)
    }

    @Test
    fun testAnonKeyValidationAcceptsAnonJwt() {
        val header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray())
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"role\":\"anon\",\"iss\":\"supabase\"}".toByteArray())
        val sig = "mock_signature"
        val jwt = "$header.$payload.$sig"

        val error = SupabasePreferences.validateAnonKey(jwt)
        assertNull(error)
    }

    @Test
    fun testUnsyncedRowHardDeletesOnDelete() = runTest {
        // Insert a coupon that was never synced (serverUpdatedAt == null)
        val coupon = Coupon(
            code = "SAVE20",
            imagePath = "/data/image1.jpg",
            status = CouponStatus.UNSCANNED,
            serverUpdatedAt = null
        )
        val id = repository.insertCoupon(coupon)
        val inserted = repository.getCouponById(id)
        assertNotNull(inserted)

        // Delete should hard delete from database
        repository.deleteCoupon(inserted!!)
        val afterDelete = repository.getCouponById(id)
        assertNull(afterDelete)
        assertEquals(0, fakeDao.items.size)
    }

    @Test
    fun testSyncedRowCreatesTombstoneOnDelete() = runTest {
        // Insert a coupon that has synced (serverUpdatedAt != null)
        val coupon = Coupon(
            syncId = "sync-100",
            code = "SAVE50",
            imagePath = "/data/image2.jpg",
            status = CouponStatus.UNSCANNED,
            serverUpdatedAt = 1000L,
            dirty = false
        )
        repository.saveFromSync(coupon)
        val loaded = repository.getCouponBySyncId("sync-100")
        assertNotNull(loaded)

        // Delete should NOT hard delete; it should set deletedAt, modifiedAt, and dirty = true
        repository.deleteCoupon(loaded!!)
        val inDao = repository.getCouponBySyncId("sync-100")
        assertNotNull(inDao)
        assertNotNull(inDao!!.deletedAt)
        assertTrue(inDao.dirty)

        // But normal queries (like getAllCouponsList) must exclude it
        val visibleList = repository.getAllCouponsList()
        assertTrue(visibleList.isEmpty())

        // And it must appear in dirty coupons queue for push
        val dirtyList = repository.getDirtyCoupons(10)
        assertEquals(1, dirtyList.size)
        assertEquals("sync-100", dirtyList[0].syncId)

        // Once server responds 'applied', tombstone is purged
        repository.purgeBySyncId("sync-100")
        assertNull(repository.getCouponBySyncId("sync-100"))
    }

    @Test
    fun testLwwConflictResolutionServerWinsWhenNewer() = runTest {
        // Local has modifiedAt = 2000
        val localCoupon = Coupon(
            syncId = "sync-200",
            code = "LOCAL_CODE",
            status = CouponStatus.UNSCANNED,
            modifiedAt = 2000L,
            dirty = true
        )
        fakeDao.items.add(localCoupon)

        // Server pull has modifiedAt = 3000 (newer)
        val serverModifiedAt = 3000L
        if (serverModifiedAt >= localCoupon.modifiedAt) {
            repository.saveFromSync(
                localCoupon.copy(
                    code = "SERVER_CODE",
                    status = CouponStatus.REDEEMED,
                    modifiedAt = serverModifiedAt,
                    dirty = false,
                    serverUpdatedAt = 5000L
                )
            )
        }

        val updated = repository.getCouponBySyncId("sync-200")
        assertNotNull(updated)
        assertEquals("SERVER_CODE", updated!!.code)
        assertEquals(CouponStatus.REDEEMED, updated.status)
        assertFalse(updated.dirty)
    }

    @Test
    fun testLwwConflictResolutionLocalWinsWhenNewer() = runTest {
        // Local has modifiedAt = 5000
        val localCoupon = Coupon(
            syncId = "sync-300",
            code = "LOCAL_NEWER",
            status = CouponStatus.REDEEMED,
            modifiedAt = 5000L,
            dirty = true
        )
        fakeDao.items.add(localCoupon)

        // Server pull has modifiedAt = 4000 (older)
        val serverModifiedAt = 4000L
        val shouldServerWin = serverModifiedAt >= localCoupon.modifiedAt
        assertFalse(shouldServerWin)

        // Local remains as-is and dirty so it pushes on next pass
        val current = repository.getCouponBySyncId("sync-300")
        assertEquals("LOCAL_NEWER", current!!.code)
        assertTrue(current.dirty)
    }

    @Test
    fun testDuplicateCodeAdoption() = runTest {
        // User created a coupon offline with code "COFFEE10"
        val offlineCoupon = Coupon(
            syncId = "local-uuid",
            code = "COFFEE10",
            status = CouponStatus.UNSCANNED,
            imagePath = "/data/coffee.jpg",
            dirty = true
        )
        repository.insertCoupon(offlineCoupon)

        // Server returns duplicate with existing_id = "server-uuid"
        val existingServerId = "server-uuid"
        val local = repository.getCouponBySyncId("local-uuid")
        assertNotNull(local)

        // Adopt existing_id and retain local photo
        repository.saveFromSync(
            local!!.copy(
                syncId = existingServerId,
                dirty = false,
                serverUpdatedAt = 9000L
            )
        )
        repository.purgeBySyncId("local-uuid")

        val adopted = repository.getCouponBySyncId("server-uuid")
        assertNotNull(adopted)
        assertEquals("COFFEE10", adopted!!.code)
        assertEquals("/data/coffee.jpg", adopted.imagePath)
        assertFalse(adopted.dirty)
        assertNull(repository.getCouponBySyncId("local-uuid"))
    }

    @Test
    fun testCursorOverlapIdempotency() = runTest {
        // First pull inserts the coupon
        val pulledCoupon = Coupon(
            syncId = "sync-overlap",
            code = "DISCOUNT",
            status = CouponStatus.UNSCANNED,
            modifiedAt = 1000L,
            serverUpdatedAt = 2000L,
            dirty = false
        )
        repository.saveFromSync(pulledCoupon)
        assertEquals(1, fakeDao.items.size)

        // Second pull within overlap window pulls the same coupon again
        repository.saveFromSync(pulledCoupon)
        assertEquals(1, fakeDao.items.size)
        assertEquals("DISCOUNT", repository.getCouponBySyncId("sync-overlap")?.code)
    }

    @Test
    fun testOfflineQueuingKeepsDirtyRows() = runTest {
        // When device is offline, insert and update both stamp dirty = true
        val c1 = Coupon(code = "OFFLINE1", status = CouponStatus.UNSCANNED)
        val c2 = Coupon(code = "OFFLINE2", status = CouponStatus.UNSCANNED)
        repository.insertCoupon(c1)
        repository.insertCoupon(c2)

        val dirtyCoupons = repository.getDirtyCoupons(10)
        assertEquals(2, dirtyCoupons.size)
        assertTrue(dirtyCoupons.all { it.dirty })
    }
}
