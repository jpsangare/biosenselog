package com.example.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.data.BiometricDatabase
import com.example.data.BiometricRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Integration: ViewModel + real Room database + SharedPreferences.
 * Run in both build types: `testDebugUnitTest` (developer build, simulator available) and
 * `testReleaseUnitTest` (store build: simulator must be impossible).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BiometricViewModelIntegrationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() {
        BiometricDatabase.resetForTests(app)
        app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        stores.forEach { it.clear() } // runs onCleared: stops simulation and sensors
        BiometricDatabase.resetForTests(app)
    }

    /** A ViewModel with a real lifecycle, as the app creates it. */
    private fun newViewModel(): BiometricViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[BiometricViewModel::class.java]
    }

    private fun savedRecords(): List<BiometricRecord> =
        runBlocking { BiometricDatabase.getDatabase(app).biometricDao().getAllRecords().first() }

    /** Lets the main thread and the database threads finish, polling until [done] or the timeout. */
    private fun awaitRecords(timeoutMs: Long = 5_000, done: (List<BiometricRecord>) -> Boolean): List<BiometricRecord> {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val records = savedRecords()
            if (done(records) || System.currentTimeMillis() > end) return records
            Thread.sleep(25)
        }
    }

    // --- Store-build safety (meaningful in both build types) ---

    @Test fun simulatorIsOnlyAvailableInDeveloperBuilds() {
        val vm = newViewModel()
        assertEquals(BuildConfig.DEBUG, vm.isSimulatorEnabled.value)

        vm.setSimulatorEnabled(true)
        assertEquals("store builds must refuse the simulator", BuildConfig.DEBUG, vm.isSimulatorEnabled.value)

        vm.setSimulatedFingerTouch(true)
        assertEquals(BuildConfig.DEBUG, vm.isSimulatedFingerTouch.value)
    }

    @Test fun storeBuildStartsWithoutFakeReadings() {
        assumeFalse("store-build behaviour; run with testReleaseUnitTest", BuildConfig.DEBUG)
        val vm = newViewModel()
        assertEquals(0, vm.liveBPM.value)
        assertEquals(0.0, vm.liveVibeRMS.value, 0.0)
    }

    @Test fun storeBuildDoesNotSaveWithoutARealPulse() {
        assumeFalse("store-build behaviour; run with testReleaseUnitTest", BuildConfig.DEBUG)
        newViewModel().logCurrentReadingToDb()
        assertTrue(awaitRecords(timeoutMs = 1_000) { false }.isEmpty())
    }

    // --- Saving ---

    @Test fun saveNowStoresTheLiveReading() {
        assumeTrue("needs the simulator's live pulse; developer build", BuildConfig.DEBUG)
        val vm = newViewModel()

        vm.logCurrentReadingToDb()
        val record = awaitRecords { it.isNotEmpty() }.single()

        assertEquals("PPG", record.type)
        assertTrue("pulse ${record.value}", record.value in 50.0..130.0)
        assertTrue(record.status in setOf("LOW", "NORMAL", "HIGH"))
        assertNull("a direct save has no recording, so no HRV", record.rmssdMs)
        assertNull(record.respiratoryRate)
    }

    @Test fun tremorIsNeverSavedWithoutAFullRecording() {
        val vm = newViewModel()
        vm.setTab("TREMOR")
        vm.logCurrentReadingToDb()
        val saved = awaitRecords(timeoutMs = 1_000) { false }
        assertTrue("unexpected: $saved", saved.isEmpty())
    }

    @Test fun clearHistoryRemovesEverything() {
        assumeTrue(BuildConfig.DEBUG)
        val vm = newViewModel()
        vm.logCurrentReadingToDb()
        awaitRecords { it.isNotEmpty() }

        vm.clearLogHistory()
        assertTrue(awaitRecords { it.isEmpty() }.isEmpty())
    }

    // --- Welcome notice ---

    @Test fun welcomeNoticeIsShownOnceThenRemembered() {
        val first = newViewModel()
        assertFalse(first.isDisclaimerAccepted.value)

        first.acceptDisclaimer()

        assertTrue("remembered after an app restart", newViewModel().isDisclaimerAccepted.value)
    }

    // --- Exports ---

    private val sample = listOf(
        BiometricRecord(id = 3, type = "TREMOR", value = 0.065, secondaryValue = 8.7, status = "INFO", timestamp = 3_000),
        BiometricRecord(id = 2, type = "VIBRATION", value = 0.12, secondaryValue = 0.0, status = "NORMAL", timestamp = 2_000, respiratoryRate = 15.0),
        BiometricRecord(id = 1, type = "PPG", value = 72.0, secondaryValue = 2.4, status = "NORMAL", timestamp = 1_000,
            notes = "say \"hi\"", rmssdMs = 42.0, sdnnMs = 50.0, respiratoryRate = 13.0)
    )

    @Test fun csvExportHasOneRowPerMeasurementWithAllColumns() {
        val lines = newViewModel().generateCsvExport(sample).trim().lines()
        val header = lines.first().split(",")

        assertEquals(4, lines.size)
        assertEquals(12, header.size)
        assertTrue(lines.any { it.contains("\"Hand Tremor\"") && it.contains("8.7 Hz") })
        val ppg = lines.single { it.startsWith("1,") }
        assertTrue(ppg, ppg.contains(",42.0,50.0,13.0,"))
        assertTrue("quotes in notes are escaped", ppg.contains("\"say \"\"hi\"\"\""))
    }

    @Test fun reportExportSummarisesEachSensor() {
        val report = newViewModel().generateReportExport(sample)
        listOf("BLOOD FLOW (PPG)", "CHEST VIBRATION (SCG)", "HAND TREMOR", "Average HRV (RMSSD)", "Average breathing rate")
            .forEach { assertTrue("missing '$it'", report.contains(it)) }
        assertTrue(report.contains("Total measurements: 3"))
    }
}
