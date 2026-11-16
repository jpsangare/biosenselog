package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration: the real Room database on a real SQLite file.
 * Covers the upgrade paths existing users go through (v1 -> v3, v2 -> v3) and the DAO queries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_test_db"
    private var db: BiometricDatabase? = null

    @Before fun setUp() { context.deleteDatabase(dbName) }

    @After fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    /** Table exactly as Room created it in version 1 of the app. */
    private val v1Table = """
        CREATE TABLE IF NOT EXISTS `biometric_records` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `value` REAL NOT NULL,
          `secondaryValue` REAL NOT NULL, `status` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `notes` TEXT NOT NULL)
    """.trimIndent()

    /** Writes an old-version database file the way an installed older app would have left it. */
    private fun createOldDatabase(version: Int, block: SQLiteDatabase.() -> Unit) {
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(v1Table)
            if (version >= 2) {
                old.execSQL("ALTER TABLE biometric_records ADD COLUMN rmssdMs REAL")
                old.execSQL("ALTER TABLE biometric_records ADD COLUMN sdnnMs REAL")
            }
            old.block()
            old.version = version
        }
    }

    private fun openCurrent(): BiometricDatabase =
        Room.databaseBuilder(context, BiometricDatabase::class.java, dbName)
            .addMigrations(BiometricDatabase.MIGRATION_1_2, BiometricDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun all(database: BiometricDatabase) = runBlocking { database.biometricDao().getAllRecords().first() }

    @Test fun upgradeFromVersion1KeepsAllMeasurements() {
        createOldDatabase(1) {
            execSQL("INSERT INTO biometric_records (type, value, secondaryValue, status, timestamp, notes) VALUES ('PPG', 72, 2.4, 'NORMAL', 1000, '')")
            execSQL("INSERT INTO biometric_records (type, value, secondaryValue, status, timestamp, notes) VALUES ('VIBRATION', 0.12, 0, 'NORMAL', 2000, 'after run')")
        }

        val records = all(openCurrent()) // Room validates the migrated schema here; a mismatch throws.

        assertEquals(2, records.size)
        val vibe = records[0] // newest first
        assertEquals("VIBRATION", vibe.type)
        assertEquals("after run", vibe.notes)
        val ppg = records[1]
        assertEquals(72.0, ppg.value, 0.0)
        assertNull(ppg.rmssdMs)
        assertNull(ppg.respiratoryRate)
    }

    @Test fun upgradeFromVersion2KeepsHrv() {
        createOldDatabase(2) {
            execSQL("INSERT INTO biometric_records (type, value, secondaryValue, status, timestamp, notes, rmssdMs, sdnnMs) VALUES ('PPG', 65, 2.0, 'NORMAL', 1000, '', 42.5, 50.1)")
        }

        val record = all(openCurrent()).single()

        assertEquals(42.5, record.rmssdMs!!, 0.0)
        assertEquals(50.1, record.sdnnMs!!, 0.0)
        assertNull(record.respiratoryRate)
    }

    @Test fun upgradedDatabaseAcceptsNewMeasurements() {
        createOldDatabase(1) {}
        val database = openCurrent()
        runBlocking {
            database.biometricDao().insertRecord(
                BiometricRecord(type = "PPG", value = 70.0, secondaryValue = 2.1, status = "NORMAL",
                    timestamp = 5000, rmssdMs = 35.0, sdnnMs = 40.0, respiratoryRate = 14.0)
            )
        }
        assertEquals(14.0, all(database).single().respiratoryRate!!, 0.0)
    }

    @Test fun daoOrdersNewestFirstAndDeletes() {
        val database = openCurrent()
        val dao = database.biometricDao()
        runBlocking {
            val oldId = dao.insertRecord(BiometricRecord(type = "PPG", value = 60.0, secondaryValue = 1.0, status = "NORMAL", timestamp = 1))
            dao.insertRecord(BiometricRecord(type = "TREMOR", value = 0.05, secondaryValue = 9.0, status = "INFO", timestamp = 3))
            dao.insertRecord(BiometricRecord(type = "VIBRATION", value = 0.1, secondaryValue = 0.0, status = "NORMAL", timestamp = 2))

            assertEquals(listOf("TREMOR", "VIBRATION", "PPG"), dao.getAllRecords().first().map { it.type })

            dao.deleteRecordById(oldId)
            assertEquals(2, dao.getAllRecords().first().size)

            dao.clearAllRecords()
            assertEquals(0, dao.getAllRecords().first().size)
        }
    }
}
