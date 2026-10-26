package com.example.couponvault.data

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private fun createDbHelper(context: Context, name: String, version: Int): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create Version 2 schema
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS coupons (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            code TEXT,
                            imagePath TEXT NOT NULL,
                            status TEXT NOT NULL,
                            source TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            redeemedAt INTEGER,
                            ocrCode TEXT
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_coupons_code ON coupons (code)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    if (oldVersion <= 2 && newVersion >= 3) {
                        MIGRATION_2_3.migrate(db)
                    }
                }
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @Test
    fun testMigrationFrom2To3PreservesDataAndMigratesOcrSourceToManual() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.cacheDir, "migration_test_${System.currentTimeMillis()}.db")
        dbFile.deleteOnExit()

        // 1. Create DB at Version 2 and insert test rows
        val helperV2 = createDbHelper(context, dbFile.absolutePath, version = 2)
        val dbV2 = helperV2.writableDatabase

        val cv1 = ContentValues().apply {
            put("id", 1L)
            put("code", "QR_CODE_12345")
            put("imagePath", "/data/img1.jpg")
            put("status", "UNSCANNED")
            put("source", "QR")
            put("createdAt", 1000L)
            putNull("redeemedAt")
            put("ocrCode", "QR_CODE_12345")
        }
        val cv2 = ContentValues().apply {
            put("id", 2L)
            put("code", "OCR_CODE_67890")
            put("imagePath", "/data/img2.jpg")
            put("status", "NEEDS_REVIEW")
            put("source", "OCR") // Existing row with OCR source
            put("createdAt", 2000L)
            putNull("redeemedAt")
            put("ocrCode", "OCR_CODE_67890")
        }
        val cv3 = ContentValues().apply {
            put("id", 3L)
            put("code", "OCR_CODE_ABCDE")
            put("imagePath", "/data/img3.jpg")
            put("status", "UNSCANNED")
            put("source", "OCR") // Existing row with OCR source
            put("createdAt", 3000L)
            put("redeemedAt", 4000L) // redeemedAt
            put("ocrCode", "OCR_CODE_ABCDE")
        }
        val cv4 = ContentValues().apply {
            put("id", 4L)
            putNull("code")
            put("imagePath", "/data/img4.jpg")
            put("status", "NEEDS_REVIEW")
            put("source", "MANUAL")
            put("createdAt", 5000L)
            putNull("redeemedAt")
            putNull("ocrCode")
        }

        dbV2.insert("coupons", 0, cv1)
        dbV2.insert("coupons", 0, cv2)
        dbV2.insert("coupons", 0, cv3)
        dbV2.insert("coupons", 0, cv4)
        helperV2.close()

        // 2. Open DB at Version 3 triggering onUpgrade -> MIGRATION_2_3
        val helperV3 = createDbHelper(context, dbFile.absolutePath, version = 3)
        val dbV3 = helperV3.writableDatabase

        // 3. Verify column list in coupons table (ocrCode column MUST be dropped)
        val columnNames = mutableListOf<String>()
        val pragmaCursor = dbV3.query("PRAGMA table_info(coupons)")
        while (pragmaCursor.moveToNext()) {
            val nameIndex = pragmaCursor.getColumnIndex("name")
            columnNames.add(pragmaCursor.getString(nameIndex))
        }
        pragmaCursor.close()

        assertTrue("Expected 'id' column", columnNames.contains("id"))
        assertTrue("Expected 'code' column", columnNames.contains("code"))
        assertTrue("Expected 'imagePath' column", columnNames.contains("imagePath"))
        assertTrue("Expected 'status' column", columnNames.contains("status"))
        assertTrue("Expected 'source' column", columnNames.contains("source"))
        assertTrue("Expected 'createdAt' column", columnNames.contains("createdAt"))
        assertTrue("Expected 'redeemedAt' column", columnNames.contains("redeemedAt"))
        assertFalse("Obsolete 'ocrCode' column must be dropped", columnNames.contains("ocrCode"))

        // 4. Verify migrated rows
        val cursor = dbV3.query("SELECT id, code, imagePath, status, source, createdAt, redeemedAt FROM coupons ORDER BY id ASC")
        assertEquals(4, cursor.count)

        // Row 1: QR source remains QR, code and status intact
        assertTrue(cursor.moveToNext())
        assertEquals(1L, cursor.getLong(0))
        assertEquals("QR_CODE_12345", cursor.getString(1))
        assertEquals("/data/img1.jpg", cursor.getString(2))
        assertEquals("UNSCANNED", cursor.getString(3))
        assertEquals("QR", cursor.getString(4))
        assertEquals(1000L, cursor.getLong(5))
        assertTrue(cursor.isNull(6))

        // Row 2: OCR source converted to MANUAL, code and status intact
        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(0))
        assertEquals("OCR_CODE_67890", cursor.getString(1))
        assertEquals("/data/img2.jpg", cursor.getString(2))
        assertEquals("NEEDS_REVIEW", cursor.getString(3))
        assertEquals("MANUAL", cursor.getString(4)) // Converted!
        assertEquals(2000L, cursor.getLong(5))
        assertTrue(cursor.isNull(6))

        // Row 3: OCR source converted to MANUAL, code and status intact
        assertTrue(cursor.moveToNext())
        assertEquals(3L, cursor.getLong(0))
        assertEquals("OCR_CODE_ABCDE", cursor.getString(1))
        assertEquals("/data/img3.jpg", cursor.getString(2))
        assertEquals("UNSCANNED", cursor.getString(3))
        assertEquals("MANUAL", cursor.getString(4)) // Converted!
        assertEquals(3000L, cursor.getLong(5))

        // Row 4: NULL code and MANUAL source intact
        assertTrue(cursor.moveToNext())
        assertEquals(4L, cursor.getLong(0))
        assertTrue(cursor.isNull(1))
        assertEquals("/data/img4.jpg", cursor.getString(2))
        assertEquals("NEEDS_REVIEW", cursor.getString(3))
        assertEquals("MANUAL", cursor.getString(4))
        assertEquals(5000L, cursor.getLong(5))

        cursor.close()
        helperV3.close()
        dbFile.delete()
    }
}
