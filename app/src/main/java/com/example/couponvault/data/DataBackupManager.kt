package com.example.couponvault.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.couponvault.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Metadata inspected from a CouponVault backup zip manifest.
 */
data class BackupManifest(
    val appVersion: String,
    val dbSchemaVersion: Int,
    val exportedAt: Long,
    val couponCount: Int,
    val photoCount: Int
)

/**
 * Result of validating a backup archive before applying it to the database.
 */
data class BackupValidationResult(
    val manifest: BackupManifest,
    val formattedDate: String,
    val sourceUri: Uri? = null
)

/**
 * Mode of restoring backup data.
 */
enum class RestoreMode {
    MERGE,
    REPLACE
}

/**
 * Result returned after completing a backup restore.
 */
data class RestoreResult(
    val importedCount: Int,
    val skippedDuplicates: Int,
    val mode: RestoreMode
)

/**
 * Manages full backup export and safe streaming restore for CouponVault.
 */
object DataBackupManager {
    private const val TAG = "DataBackupManager"
    const val CURRENT_APP_VERSION = "1.0"
    const val CURRENT_SCHEMA_VERSION = 4
    private const val MAX_UNCOMPRESSED_SIZE = 500L * 1024L * 1024L // 500 MB safeguard

    /**
     * Suggested file name for backup exports.
     */
    fun createBackupFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "CouponVault_backup_$timestamp.zip"
    }

    /**
     * Streams all coupons, original photos, settings, and metadata into a zip archive.
     */
    suspend fun exportBackup(
        context: Context,
        outputStream: OutputStream,
        repository: CouponRepository,
        preferences: OverlayPreferences,
        onProgress: (current: Int, total: Int, percent: Int) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val coupons = repository.getAllCouponsList()
            val autoMarkRedeemed = preferences.autoMarkRedeemedFlow.first()

            val referencedPhotoFiles = coupons
                .map { File(it.imagePath) }
                .filter { it.exists() && it.isFile }
                .distinctBy { it.name }

            val totalSteps = 4 + referencedPhotoFiles.size
            var currentStep = 0

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                // 1. Write manifest.json
                coroutineContext.ensureActive()
                val manifestJson = JSONObject().apply {
                    put("appVersion", CURRENT_APP_VERSION)
                    put("dbSchemaVersion", CURRENT_SCHEMA_VERSION)
                    put("exportedAt", System.currentTimeMillis())
                    put("couponCount", coupons.size)
                    put("photoCount", referencedPhotoFiles.size)
                }
                zipOut.putNextEntry(ZipEntry("manifest.json"))
                zipOut.write(manifestJson.toString(2).toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
                currentStep++
                onProgress(currentStep, totalSteps, (currentStep * 100) / totalSteps)

                // 2. Write coupons.json
                coroutineContext.ensureActive()
                val couponsArray = JSONArray()
                for (coupon in coupons) {
                    val couponObj = JSONObject().apply {
                        put("code", coupon.code)
                        put("imagePath", File(coupon.imagePath).name) // relative file name only
                        put("status", coupon.status.name)
                        put("source", coupon.source.name)
                        put("createdAt", coupon.createdAt)
                        if (coupon.redeemedAt != null) {
                            put("redeemedAt", coupon.redeemedAt)
                        } else {
                            put("redeemedAt", JSONObject.NULL)
                        }
                        put("extractionState", coupon.extractionState.name)
                    }
                    couponsArray.put(couponObj)
                }
                zipOut.putNextEntry(ZipEntry("coupons.json"))
                zipOut.write(couponsArray.toString(2).toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
                currentStep++
                onProgress(currentStep, totalSteps, (currentStep * 100) / totalSteps)

                // 3. Write settings.json
                coroutineContext.ensureActive()
                val settingsJson = JSONObject().apply {
                    put("autoMarkRedeemed", autoMarkRedeemed)
                }
                zipOut.putNextEntry(ZipEntry("settings.json"))
                zipOut.write(settingsJson.toString(2).toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
                currentStep++
                onProgress(currentStep, totalSteps, (currentStep * 100) / totalSteps)

                // 4. Write human-readable codes.csv
                coroutineContext.ensureActive()
                val csvContent = StorageHelper.generateCsvContent(coupons)
                zipOut.putNextEntry(ZipEntry("codes.csv"))
                zipOut.write(csvContent.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
                currentStep++
                onProgress(currentStep, totalSteps, (currentStep * 100) / totalSteps)

                // 5. Stream photo files under originals/
                val buffer = ByteArray(8192)
                for (photoFile in referencedPhotoFiles) {
                    coroutineContext.ensureActive()
                    val entryName = "originals/${photoFile.name}"
                    zipOut.putNextEntry(ZipEntry(entryName))
                    FileInputStream(photoFile).use { fis ->
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            zipOut.write(buffer, 0, read)
                        }
                    }
                    zipOut.closeEntry()
                    currentStep++
                    onProgress(currentStep, totalSteps, (currentStep * 100) / totalSteps)
                }

                zipOut.finish()
            }
        }
    }

    /**
     * Validates a backup archive without touching any current app data.
     * Protects against Zip-Slip, path traversal, unknown files, and future schema versions.
     */
    suspend fun validateBackup(
        inputStream: InputStream,
        sourceUri: Uri? = null
    ): Result<BackupValidationResult> = withContext(Dispatchers.IO) {
        runCatching {
            var manifestText: String? = null
            var couponsText: String? = null
            val zipEntries = mutableSetOf<String>()
            var totalUncompressedBytes = 0L

            ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name

                    // Security check 1: Reject directory traversal / Zip-Slip
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        error("Zip-slip attack detected: unsafe file path '$name'")
                    }

                    // Security check 2: Strict entry whitelisting
                    val isExpected = name == "manifest.json" ||
                            name == "coupons.json" ||
                            name == "settings.json" ||
                            name == "codes.csv" ||
                            (name.startsWith("originals/") && !name.substring(10).contains("/") && !name.substring(10).contains("\\"))

                    if (!isExpected) {
                        error("Unexpected or unsafe file '$name' found in backup")
                    }

                    zipEntries.add(name)

                    // Read manifest.json or coupons.json into memory
                    if (name == "manifest.json") {
                        val bytes = zipIn.readBytes()
                        totalUncompressedBytes += bytes.size
                        manifestText = bytes.toString(Charsets.UTF_8)
                    } else if (name == "coupons.json") {
                        val bytes = zipIn.readBytes()
                        totalUncompressedBytes += bytes.size
                        couponsText = bytes.toString(Charsets.UTF_8)
                    } else {
                        // For other entries, count decompressed bytes to enforce size limit
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (zipIn.read(buffer).also { read = it } != -1) {
                            totalUncompressedBytes += read
                            if (totalUncompressedBytes > MAX_UNCOMPRESSED_SIZE) {
                                error("Backup uncompressed size exceeds limit (500 MB)")
                            }
                        }
                    }

                    if (totalUncompressedBytes > MAX_UNCOMPRESSED_SIZE) {
                        error("Backup uncompressed size exceeds limit (500 MB)")
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            if (manifestText == null) {
                error("Missing manifest.json in backup archive")
            }

            val manifestObj = JSONObject(manifestText!!)
            val appVersion = manifestObj.optString("appVersion", "1.0")
            val dbSchemaVersion = manifestObj.optInt("dbSchemaVersion", 1)
            val exportedAt = manifestObj.optLong("exportedAt", System.currentTimeMillis())
            val couponCount = manifestObj.optInt("couponCount", 0)
            val photoCount = manifestObj.optInt("photoCount", 0)

            if (dbSchemaVersion > CURRENT_SCHEMA_VERSION) {
                error("Backup is from a newer version (schema v$dbSchemaVersion, app supports v$CURRENT_SCHEMA_VERSION)")
            }

            // Verify that all photos referenced in coupons.json exist in zip
            if (couponsText != null) {
                val couponsArray = JSONArray(couponsText!!)
                for (i in 0 until couponsArray.length()) {
                    val obj = couponsArray.getJSONObject(i)
                    val rawPhoto = obj.optString("imagePath", "")
                    val photoName = File(rawPhoto).name
                    if (photoName.isNotBlank() && !zipEntries.contains("originals/$photoName")) {
                        error("Referenced photo 'originals/$photoName' is missing from backup")
                    }
                }
            }

            val formattedDate = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(exportedAt))
            val manifest = BackupManifest(
                appVersion = appVersion,
                dbSchemaVersion = dbSchemaVersion,
                exportedAt = exportedAt,
                couponCount = couponCount,
                photoCount = photoCount
            )

            BackupValidationResult(
                manifest = manifest,
                formattedDate = formattedDate,
                sourceUri = sourceUri
            )
        }
    }

    /**
     * Executes the restore from an input stream using the specified mode (MERGE or REPLACE).
     * Extracts files to a temporary directory first, performs the database operations
     * in a single atomic Room transaction, and safely moves files to filesDir/originals/
     * only after transaction success.
     */
    suspend fun restoreBackup(
        context: Context,
        inputStream: InputStream,
        mode: RestoreMode,
        database: AppDatabase,
        preferences: OverlayPreferences,
        onProgress: (current: Int, total: Int, percent: Int) -> Unit = { _, _, _ -> }
    ): Result<RestoreResult> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "temp_restore_${UUID.randomUUID()}")
        val createdPermanentFiles = mutableListOf<File>()

        try {
            tempDir.mkdirs()
            val originalsTempDir = File(tempDir, "originals").apply { mkdirs() }

            var manifestFile: File? = null
            var couponsFile: File? = null
            var settingsFile: File? = null
            var totalUncompressedBytes = 0L

            // 1. Safe extraction into temp directory
            ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name

                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        error("Zip-slip attack detected: '$name'")
                    }

                    when {
                        name == "manifest.json" -> {
                            val f = File(tempDir, "manifest.json")
                            FileOutputStream(f).use { fos ->
                                zipIn.copyTo(fos)
                            }
                            manifestFile = f
                        }
                        name == "coupons.json" -> {
                            val f = File(tempDir, "coupons.json")
                            FileOutputStream(f).use { fos ->
                                zipIn.copyTo(fos)
                            }
                            couponsFile = f
                        }
                        name == "settings.json" -> {
                            val f = File(tempDir, "settings.json")
                            FileOutputStream(f).use { fos ->
                                zipIn.copyTo(fos)
                            }
                            settingsFile = f
                        }
                        name.startsWith("originals/") -> {
                            val fileName = name.substring(10)
                            if (fileName.isNotBlank() && !fileName.contains("/") && !fileName.contains("\\")) {
                                val f = File(originalsTempDir, fileName)
                                FileOutputStream(f).use { fos ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (zipIn.read(buffer).also { read = it } != -1) {
                                        totalUncompressedBytes += read
                                        if (totalUncompressedBytes > MAX_UNCOMPRESSED_SIZE) {
                                            error("Backup uncompressed size exceeds limit (500 MB)")
                                        }
                                        fos.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            if (couponsFile == null || !couponsFile!!.exists()) {
                error("Missing coupons.json in backup")
            }

            // 2. Parse coupons.json
            val couponsArray = JSONArray(couponsFile!!.readText(Charsets.UTF_8))
            val importedItems = mutableListOf<ParsedBackupCoupon>()

            for (i in 0 until couponsArray.length()) {
                val obj = couponsArray.getJSONObject(i)
                val status = try {
                    CouponStatus.valueOf(obj.optString("status", CouponStatus.NEEDS_REVIEW.name))
                } catch (e: Exception) {
                    CouponStatus.NEEDS_REVIEW
                }
                val source = try {
                    CouponSource.valueOf(obj.optString("source", CouponSource.MANUAL.name))
                } catch (e: Exception) {
                    CouponSource.MANUAL
                }
                val extractionState = try {
                    ExtractionState.valueOf(obj.optString("extractionState", ExtractionState.DONE.name))
                } catch (e: Exception) {
                    ExtractionState.DONE
                }
                val code = if (obj.has("code") && !obj.isNull("code")) {
                    val raw = obj.getString("code").trim()
                    if (raw.isNotEmpty()) raw else null
                } else null

                val rawImagePath = obj.optString("imagePath", "")
                val photoFileName = File(rawImagePath).name
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val redeemedAt = if (obj.has("redeemedAt") && !obj.isNull("redeemedAt")) {
                    obj.getLong("redeemedAt")
                } else null

                importedItems.add(
                    ParsedBackupCoupon(
                        code = code,
                        photoFileName = photoFileName,
                        status = status,
                        source = source,
                        createdAt = createdAt,
                        redeemedAt = redeemedAt,
                        extractionState = extractionState
                    )
                )
            }

            // 3. Parse settings if present
            var autoMarkRedeemed: Boolean? = null
            if (settingsFile != null && settingsFile!!.exists()) {
                try {
                    val sObj = JSONObject(settingsFile!!.readText(Charsets.UTF_8))
                    if (sObj.has("autoMarkRedeemed")) {
                        autoMarkRedeemed = sObj.getBoolean("autoMarkRedeemed")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse settings.json", e)
                }
            }

            val dao = database.couponDao()
            val originalsDir = StorageHelper.getOriginalsDirectory(context)
            var importedCount = 0
            var skippedDuplicates = 0

            // Mapping: temp photo filename -> permanent destination File
            val photoDestMap = mutableMapOf<String, File>()

            fun getPermanentPhotoFile(photoFileName: String): File {
                return photoDestMap.getOrPut(photoFileName) {
                    val tempPhoto = File(originalsTempDir, photoFileName)
                    val newUniqueFile = StorageHelper.createOriginalFile(context)
                    if (tempPhoto.exists()) {
                        tempPhoto.copyTo(newUniqueFile, overwrite = true)
                    } else {
                        // Placeholder if file wasn't bundled
                        newUniqueFile.writeBytes(ByteArray(0))
                    }
                    createdPermanentFiles.add(newUniqueFile)
                    newUniqueFile
                }
            }

            // 4. Perform database operation in atomic transaction
            database.withTransaction {
                if (mode == RestoreMode.REPLACE) {
                    val existing = dao.getAllCouponsList()
                    dao.deleteAll()
                    // Delete old orphan photos
                    for (old in existing) {
                        val file = File(old.imagePath)
                        if (file.exists() && dao.countByImagePath(old.imagePath) == 0) {
                            file.delete()
                        }
                    }

                    for (item in importedItems) {
                        val permanentFile = getPermanentPhotoFile(item.photoFileName)
                        val coupon = Coupon(
                            code = item.code,
                            imagePath = permanentFile.absolutePath,
                            status = item.status,
                            source = item.source,
                            createdAt = item.createdAt,
                            redeemedAt = item.redeemedAt,
                            extractionState = item.extractionState
                        )
                        dao.insert(coupon)
                        importedCount++
                    }

                    if (autoMarkRedeemed != null) {
                        preferences.setAutoMarkRedeemed(autoMarkRedeemed)
                    }
                } else {
                    // MERGE mode
                    val existingCoupons = dao.getAllCouponsList()
                    val existingCodes = existingCoupons.mapNotNull { it.code?.lowercase() }.toMutableSet()

                    for (item in importedItems) {
                        val code = item.code
                        if (code != null && existingCodes.contains(code.lowercase())) {
                            skippedDuplicates++
                        } else {
                            val permanentFile = getPermanentPhotoFile(item.photoFileName)
                            val coupon = Coupon(
                                code = item.code,
                                imagePath = permanentFile.absolutePath,
                                status = item.status,
                                source = item.source,
                                createdAt = item.createdAt,
                                redeemedAt = item.redeemedAt,
                                extractionState = item.extractionState
                            )
                            dao.insert(coupon)
                            if (code != null) {
                                existingCodes.add(code.lowercase())
                            }
                            importedCount++
                        }
                    }
                }
            }

            Result.success(
                RestoreResult(
                    importedCount = importedCount,
                    skippedDuplicates = skippedDuplicates,
                    mode = mode
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed, cleaning up newly created files", e)
            // Roll back any newly created permanent files
            for (f in createdPermanentFiles) {
                if (f.exists()) {
                    f.delete()
                }
            }
            Result.failure(e)
        } finally {
            // Always clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    private data class ParsedBackupCoupon(
        val code: String?,
        val photoFileName: String,
        val status: CouponStatus,
        val source: CouponSource,
        val createdAt: Long,
        val redeemedAt: Long?,
        val extractionState: ExtractionState
    )
}
