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

@Database(
    entities = [Coupon::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun couponDao(): CouponDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "coupon_vault.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also {
                        INSTANCE = it
                    }
            }
        }
    }
}
