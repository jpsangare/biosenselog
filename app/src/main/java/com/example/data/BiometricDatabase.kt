package com.example.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// The schema of each version is exported to app/schemas/ so future migrations can be tested against it.
@Database(entities = [BiometricRecord::class], version = 3, exportSchema = true)
abstract class BiometricDatabase : RoomDatabase() {
    abstract fun biometricDao(): BiometricDao

    companion object {
        @Volatile
        private var INSTANCE: BiometricDatabase? = null

        /** v2: heart rate variability columns (existing rows keep null). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE biometric_records ADD COLUMN rmssdMs REAL")
                db.execSQL("ALTER TABLE biometric_records ADD COLUMN sdnnMs REAL")
            }
        }

        /** v3: breathing rate column (existing rows keep null). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE biometric_records ADD COLUMN respiratoryRate REAL")
            }
        }

        const val DATABASE_NAME = "biosense_biometric_db"

        /**
         * Tests only: empties and closes the shared database. Tables are cleared through Room rather
         * than only deleting the file, because Windows cannot delete a file that is still open.
         */
        @VisibleForTesting
        fun resetForTests(context: Context) = synchronized(this) {
            val db = INSTANCE ?: getDatabase(context)
            // Room forbids clearing tables on the main thread.
            Thread { db.clearAllTables() }.apply { start(); join() }
            db.close()
            INSTANCE = null
            context.applicationContext.deleteDatabase(DATABASE_NAME)
        }

        fun getDatabase(context: Context): BiometricDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BiometricDatabase::class.java,
                    DATABASE_NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
