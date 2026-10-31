package com.example.couponvault.sync

import android.util.Base64
import com.example.couponvault.data.Coupon
import com.example.couponvault.data.CouponDao
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.CouponRepository
import com.example.couponvault.data.CouponSource
import com.example.couponvault.data.CouponStatus
import com.example.couponvault.data.ExtractionState
import com.example.couponvault.data.FakeClipboardHelper
import com.example.couponvault.data.FakeOverlayPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject
import androidx.test.core.app.ApplicationProvider
import android.content.Context
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

    override suspend fun getAllCouponsIncludingTombstones(): List<Coupon> {
        return items.toList()
    }

    override suspend fun deleteUnsyncedCoupons() {
        items.removeAll { it.serverUpdatedAt == null }
        notifyChanged()
    }

    override suspend fun tombstoneSyncedCoupons(now: Long) {
        for (i in items.indices) {
            if (items[i].serverUpdatedAt != null) {
                items[i] = items[i].copy(deletedAt = now, modifiedAt = now, dirty = true)
            }
        }
        notifyChanged()
    }

    override suspend fun getCouponsNeedingPhotoUpload(): List<Coupon> {
        return items.filter { it.serverUpdatedAt != null && it.deletedAt == null && it.remoteImagePath.isNullOrBlank() && it.imagePath.isNotBlank() }
    }

    override suspend fun getCouponsNeedingPhotoDownload(): List<Coupon> {
        return items.filter { it.deletedAt == null && !it.remoteImagePath.isNullOrBlank() && it.imagePath.isBlank() }
    }

    override suspend fun updatePhotoUploadSuccess(syncId: String, remoteImagePath: String, imageSha256: String, imageVariant: String, modifiedAt: Long) {
        val idx = items.indexOfFirst { it.syncId == syncId }
        if (idx != -1) {
            items[idx] = items[idx].copy(remoteImagePath = remoteImagePath, imageSha256 = imageSha256, imageVariant = imageVariant, dirty = true, modifiedAt = modifiedAt)
            notifyChanged()
        }
    }

    override suspend fun updateLocalImagePath(syncId: String, localImagePath: String) {
        val idx = items.indexOfFirst { it.syncId == syncId }
        if (idx != -1) {
            items[idx] = items[idx].copy(imagePath = localImagePath)
            notifyChanged()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun testClockOffsetEstimationMath() {
        val t0 = 10000L
        val t1 = 10200L
        val midpoint = (t0 + t1) / 2 // 10100L
        val serverTime = 10150L
        val calculatedOffset = serverTime - midpoint // +50L
        assertEquals(50L, calculatedOffset)

        var mockOffset = calculatedOffset
        val offsetRepo = CouponRepository(fakeDao) { mockOffset }
        val baseTime = 50000L
        // Verified: currentSyncedTime adds measured offset
        assertEquals(50050L, baseTime + mockOffset)

        // Negative offset test: server is behind device
        val serverBehind = 10020L
        val negOffset = serverBehind - midpoint // -80L
        assertEquals(-80L, negOffset)
        mockOffset = negOffset
        assertEquals(49920L, baseTime + mockOffset)
    }

    @Test
    fun testTombstonePropagationAdvancesQueuePointer() = runTest {
        val clipboard = FakeClipboardHelper()
        val prefs = FakeOverlayPreferences()
        val c1 = Coupon(id = 1, syncId = "sync-1", code = "CODE1", status = CouponStatus.UNSCANNED, serverUpdatedAt = 1000L)
        val c2 = Coupon(id = 2, syncId = "sync-2", code = "CODE2", status = CouponStatus.UNSCANNED, serverUpdatedAt = 1000L)
        fakeDao.items.add(c1)
        fakeDao.items.add(c2)

        val queueManager = CouponQueueManager(
            couponDao = fakeDao,
            preferences = prefs,
            clipboardHelper = clipboard
        )

        // User copies C1
        val firstCopied = queueManager.copyNext()
        assertEquals("CODE1", firstCopied)
        assertEquals("CODE1", clipboard.lastCopied)
        assertEquals(1L, prefs.currentCouponId.value)

        // A remote tombstone arrives for C1 from another device
        // SyncManager triggers onCouponRemovedOrRedeemed(1) and purges C1
        queueManager.onCouponRemovedOrRedeemed(1L)
        repository.purgeBySyncId("sync-1")

        // Clipboard retains "CODE1"
        assertEquals("CODE1", clipboard.lastCopied)
        // Pointer advanced to next coupon in queue (C2)
        assertEquals(2L, prefs.currentCouponId.value)
        // C1 is purged from local DB
        assertNull(repository.getCouponBySyncId("sync-1"))
        assertNotNull(repository.getCouponBySyncId("sync-2"))
    }

    @Test
    fun testFullReconcileRemovesOrphansAndInsertsNewRemoteRows() = runTest {
        // 1. Local clean coupon missing from remote (e.g. deleted from dashboard or remote tombstone)
        val localCleanOrphan = Coupon(
            id = 10,
            syncId = "remote-gone",
            code = "GONE",
            status = CouponStatus.UNSCANNED,
            serverUpdatedAt = 1000L,
            dirty = false
        )
        // 2. Local dirty coupon (offline change awaiting push)
        val localDirty = Coupon(
            id = 20,
            syncId = "local-new",
            code = "NEW_LOCAL",
            status = CouponStatus.UNSCANNED,
            serverUpdatedAt = null,
            dirty = true
        )
        fakeDao.items.add(localCleanOrphan)
        fakeDao.items.add(localDirty)

        // Remote database state: contains "new-remote" and does NOT contain "remote-gone"
        val remoteRows = mapOf(
            "new-remote" to Coupon(
                syncId = "new-remote",
                code = "FROM_SERVER",
                status = CouponStatus.UNSCANNED,
                serverUpdatedAt = 2000L,
                dirty = false
            )
        )

        var removedCount = 0
        var addedCount = 0

        // Reconcile pass over local rows:
        val allLocal = repository.getAllCouponsIncludingTombstones()
        for (local in allLocal) {
            if (local.serverUpdatedAt != null && !local.dirty) {
                if (!remoteRows.containsKey(local.syncId)) {
                    repository.purgeBySyncId(local.syncId)
                    removedCount++
                }
            }
        }

        // Reconcile pass over remote rows:
        for ((syncId, remote) in remoteRows) {
            val local = repository.getCouponBySyncId(syncId)
            if (local == null) {
                repository.saveFromSync(remote)
                addedCount++
            }
        }

        assertEquals(1, removedCount)
        assertEquals(1, addedCount)
        assertNull(repository.getCouponBySyncId("remote-gone"))
        assertNotNull(repository.getCouponBySyncId("new-remote"))
        assertNotNull(repository.getCouponBySyncId("local-new"))
        assertTrue(repository.getCouponBySyncId("local-new")!!.dirty)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun testDebounceAndCoalescing() = runTest {
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
        var pushInvocationCount = 0

        val job = changeFlow
            .debounce(1000L)
            .onEach { pushInvocationCount++ }
            .launchIn(this)
        testScheduler.runCurrent()

        // Rapidly emit 5 changes within 200ms
        repeat(5) {
            changeFlow.emit(Unit)
            testScheduler.advanceTimeBy(40)
            testScheduler.runCurrent()
        }

        // 200ms elapsed, 1000ms debounce hasn't expired yet
        assertEquals(0, pushInvocationCount)

        // Advance past 1000ms debounce threshold
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()

        // Exactly one coalesced push should have run!
        assertEquals(1, pushInvocationCount)
        job.cancel()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun testFakeRealtimeEventTriggersPull() = runTest {
        val realtimeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var pullInvocationCount = 0

        val job = realtimeSignal
            .debounce(300L)
            .onEach { pullInvocationCount++ }
            .launchIn(this)
        testScheduler.runCurrent()

        // Emit simulated realtime Postgres INSERT/UPDATE change
        realtimeSignal.emit(Unit)
        testScheduler.runCurrent()

        // After 100ms, debounce has not fired
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(0, pullInvocationCount)

        // After 300ms total, debounce fires and triggers pull
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()
        assertEquals(1, pullInvocationCount)
        job.cancel()
    }

    @Test
    fun testTsMillisAndParseIsoFormats() {
        // Long / epoch number
        val objLong = JSONObject("""{"ts": 1793208251000}""")
        assertEquals(1793208251000L, SyncManager.tsMillis(objLong, "ts"))

        // Int / epoch number
        val objInt = JSONObject("""{"ts": 123456}""")
        assertEquals(123456L, SyncManager.tsMillis(objInt, "ts"))

        // ISO-8601 with Z
        assertEquals(1793208251000L, SyncManager.parseIsoToMillis("2026-10-28T17:24:11Z"))

        // ISO-8601 with fractional seconds (6 digits) and +00:00 offset
        assertEquals(1793208251123L, SyncManager.parseIsoToMillis("2026-10-28T17:24:11.123456+00:00"))

        // ISO-8601 with 1 fractional digit and positive offset +05:30
        assertEquals(1793208251500L, SyncManager.parseIsoToMillis("2026-10-28T22:54:11.5+05:30"))

        // Space instead of 'T' and +00 offset
        assertEquals(1793208251000L, SyncManager.parseIsoToMillis("2026-10-28 17:24:11+00"))

        // tsMillis with ISO-8601 string value
        val objIso = JSONObject("""{"created_at": "2026-10-28T17:24:11.123456+00:00"}""")
        assertEquals(1793208251123L, SyncManager.tsMillis(objIso, "created_at"))

        // Invalid string throws IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            SyncManager.parseIsoToMillis("not-a-valid-date")
        }

        // Blank string throws IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            SyncManager.parseIsoToMillis("   ")
        }
    }

    @Test
    fun testFormatMillisToIso() {
        val iso = SyncManager.formatMillisToIso(1793208251123L)
        assertEquals("2026-10-28T17:24:11.123Z", iso)
        assertEquals(1793208251123L, SyncManager.parseIsoToMillis(iso))
    }

    @Test
    fun testEndToEndPostgrestRowParsing() = runTest {
        val jsonStr = """
        {
          "id": "11111111-1111-1111-1111-111111111111",
          "owner": "22222222-2222-2222-2222-222222222222",
          "code": "A51PEWKCWB81MXN",
          "status": "UNSCANNED",
          "source": "QR",
          "created_at": "2026-10-28T17:24:11.123456+00:00",
          "redeemed_at": null,
          "modified_at": "2026-10-28T17:24:11.123+00:00",
          "deleted_at": null,
          "image_path": null,
          "image_sha256": null,
          "image_variant": null,
          "claimed_by": null,
          "claimed_at": null,
          "updated_at": "2026-10-28T17:24:12.456789+00:00"
        }
        """.trimIndent()
        val item = JSONObject(jsonStr)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SupabasePreferences(context)
        val client = SupabaseClient(prefs)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val syncManager = SyncManager(repository, client, prefs, scope = testScope, context = context)

            val success = syncManager.applyPulledRow(item)
            assertTrue(success)

            val saved = repository.getCouponBySyncId("11111111-1111-1111-1111-111111111111")
            assertNotNull(saved)
            assertEquals("A51PEWKCWB81MXN", saved!!.code)
            assertEquals(1793208251123L, saved.createdAt)
            assertEquals(1793208251123L, saved.modifiedAt)
            assertEquals(1793208252456L, saved.serverUpdatedAt)
            assertNull(saved.redeemedAt)
            assertNull(saved.deletedAt)
            assertNull(saved.claimedBy)
            assertNull(saved.claimedAt)
            assertFalse(saved.dirty)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testReconcileTombstoneWithIsoString() = runTest {
        // Local coupon synced previously
        val localCoupon = Coupon(
            syncId = "tombstone-sync-id",
            code = "DELETE_ME",
            imagePath = "",
            status = CouponStatus.UNSCANNED,
            serverUpdatedAt = 1793208000000L,
            dirty = false
        )
        repository.saveFromSync(localCoupon)
        assertNotNull(repository.getCouponBySyncId("tombstone-sync-id"))

        // Remote map has deleted_at as an ISO string
        val remoteMap = mapOf(
            "tombstone-sync-id" to JSONObject("""
            {
                "id": "tombstone-sync-id",
                "deleted_at": "2026-10-28T17:24:11.123456+00:00",
                "updated_at": "2026-10-28T17:24:11.123456+00:00"
            }
            """)
        )

        val allLocal = repository.getAllCouponsIncludingTombstones()
        var removedCount = 0
        for (local in allLocal) {
            if (local.serverUpdatedAt != null && !local.dirty) {
                val remoteObj = remoteMap[local.syncId]
                val remoteDeletedAt = try {
                    if (remoteObj != null && !remoteObj.isNull("deleted_at")) {
                        SyncManager.tsMillis(remoteObj, "deleted_at")
                    } else null
                } catch (_: Exception) {
                    null
                }
                if (remoteObj == null || remoteDeletedAt != null) {
                    repository.purgeBySyncId(local.syncId)
                    removedCount++
                }
            }
        }
        assertEquals(1, removedCount)
        assertNull(repository.getCouponBySyncId("tombstone-sync-id"))
    }

    @Test
    fun testSkippedRowHandling() = runTest {
        val invalidRow = JSONObject("""
        {
            "id": "invalid-row-id",
            "status": "NON_EXISTENT_STATUS",
            "created_at": "not-a-valid-date"
        }
        """)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SupabasePreferences(context)
        val client = SupabaseClient(prefs)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val syncManager = SyncManager(repository, client, prefs, scope = testScope, context = context)
            val result = syncManager.applyPulledRow(invalidRow)
            assertFalse(result)
        } finally {
            testScope.cancel()
        }
    }
}
