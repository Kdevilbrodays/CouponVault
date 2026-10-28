package com.example.couponvault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE coupons ADD COLUMN ocrCode TEXT DEFAULT NULL")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS coupons_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                code TEXT,
                imagePath TEXT NOT NULL,
                status TEXT NOT NULL,
                source TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                redeemedAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO coupons_new (id, code, imagePath, status, source, createdAt, redeemedAt)
            SELECT id, code, imagePath, status,
                   CASE WHEN source = 'OCR' THEN 'MANUAL' ELSE source END,
                   createdAt, redeemedAt
            FROM coupons
            """.trimIndent()
        )
        db.execSQL("DROP TABLE coupons")
        db.execSQL("ALTER TABLE coupons_new RENAME TO coupons")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_coupons_code ON coupons (code)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE coupons ADD COLUMN extractionState TEXT NOT NULL DEFAULT 'DONE'")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE coupons ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE coupons ADD COLUMN modifiedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE coupons ADD COLUMN deletedAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE coupons ADD COLUMN serverUpdatedAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN remoteImagePath TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN imageSha256 TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN imageVariant TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN claimedBy TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN claimedAt INTEGER DEFAULT NULL")

        // Backfill modifiedAt with createdAt
        db.execSQL("UPDATE coupons SET modifiedAt = createdAt WHERE modifiedAt = 0")

        // Backfill syncId with unique random hex string for existing rows
        db.execSQL("UPDATE coupons SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_coupons_syncId ON coupons (syncId)")
    }
}

@Database(
    entities = [Coupon::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun couponDao(): CouponDao

    companion object {
        const val SCHEMA_VERSION = 5

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "coupon_vault.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also {
                        INSTANCE = it
                    }
            }
        }
    }
}
