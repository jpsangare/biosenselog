package com.example.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.BiometricDatabase
import com.example.data.BiometricRecord
import com.example.sensor.FingerDetectionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.PI
import kotlin.math.sin

/**
 * Integration: a full real-sensor measurement through the ViewModel, from raw samples to the saved
 * record. Synthetic camera / accelerometer recordings with known pulse, breathing and tremor are fed
 * in; the measurement countdown runs on Robolectric's simulated clock, so a 60 s test takes seconds.
 * Runs in both builds (real-sensor mode is the store build's only mode).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeasurementRecordingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val stores = mutableListOf<ViewModelStore>()
    private val t0 = 1_000_000L

    @Before fun setUp() {
        BiometricDatabase.resetForTests(app)
        app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        BiometricDatabase.resetForTests(app)
    }

    /** ViewModel in real-sensor mode, as in the store build. */
    private fun realSensorViewModel(): BiometricViewModel {
        val store = ViewModelStore().also { stores += it }
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[BiometricViewModel::class.java]
        vm.setSimulatorEnabled(false)
        return vm
    }

    /** Runs the measurement countdown to its end on the simulated clock. */
    private fun finishCountdown(seconds: Int) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds + 1L))
    }

    private fun awaitRecords(timeoutMs: Long = 10_000, done: (List<BiometricRecord>) -> Boolean): List<BiometricRecord> {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val records = runBlocking { BiometricDatabase.getDatabase(app).biometricDao().getAllRecords().first() }
            if (done(records) || System.currentTimeMillis() > end) return records
            Thread.sleep(25)
        }
    }

    /** Fingertip camera signal: 72 BPM pulse with a dicrotic wave, breathing at 12 per minute. */
    private fun feedFingertip(vm: BiometricViewModel, seconds: Int) {
        val fps = 30
        val heart = 72.0 / 60.0
        for (i in 0 until seconds * fps) {
            val ms = t0 + i * 1000L / fps
            val s = (ms - t0) / 1000.0
            val value = 140.0 + 12.0 * sin(2 * PI * heart * s) + 3.5 * sin(4 * PI * heart * s + 0.8) +
                2.0 * sin(2 * PI * 0.2 * s)
            vm.onCameraFrameUpdated(value, true, FingerDetectionStatus.DETECTED, currentTime = ms)
        }
    }

    /** Phone flat on the chest at 16 Hz: breathing at 15 per minute tilts the phone (y axis). */
    private fun feedChest(vm: BiometricViewModel, seconds: Int) {
        val hz = 16
        for (i in 0 until seconds * hz) {
            val ms = t0 + i * 1000L / hz
            val s = (ms - t0) / 1000.0
            val wobble = 0.005 * sin(i * 1.7) // small deterministic sensor noise
            vm.onAccelerometerSample(
                (0.3 + wobble).toFloat(),
                (0.4 + 0.06 * sin(2 * PI * 0.25 * s) + wobble).toFloat(),
                (9.8 + wobble).toFloat(),
                ms
            )
        }
    }

    /** Phone held at arm's length at 50 Hz: 10 Hz tremor of 0.1 m/s² on the x axis. */
    private fun feedTremor(vm: BiometricViewModel, seconds: Int) {
        val hz = 50
        for (i in 0 until seconds * hz) {
            val ms = t0 + i * 1000L / hz
            val s = (ms - t0) / 1000.0
            vm.onAccelerometerSample((0.1 * sin(2 * PI * 10.0 * s)).toFloat(), 0f, 9.81f, ms)
        }
    }

    @Test fun sixtySecondPulseMeasurementSavesHrvAndBreathingRate() {
        val vm = realSensorViewModel()
        vm.setMeasurementDuration(BiometricViewModel.LONG_DURATION_SEC)
        vm.startMeasurementSecured()
        feedFingertip(vm, seconds = 60)
        finishCountdown(BiometricViewModel.LONG_DURATION_SEC)

        val record = awaitRecords { it.isNotEmpty() }.single()
        assertEquals("PPG", record.type)
        assertEquals(72.0, record.value, 3.0)
        assertNotNull("HRV from the full recording", record.rmssdMs)
        assertTrue("steady synthetic pulse: low HRV, got ${record.rmssdMs}", record.rmssdMs!! < 25.0)
        assertNotNull(record.sdnnMs)
        assertEquals("breathing from the camera signal", 12.0, record.respiratoryRate!!, 1.5)
    }

    @Test fun quickPulseMeasurementHasNoBreathingRate() {
        val vm = realSensorViewModel()
        vm.setMeasurementDuration(BiometricViewModel.QUICK_DURATION_SEC)
        vm.startMeasurementSecured()
        feedFingertip(vm, seconds = 10)
        finishCountdown(BiometricViewModel.QUICK_DURATION_SEC)

        val record = awaitRecords { it.isNotEmpty() }.single()
        assertEquals(72.0, record.value, 3.0)
        assertNull("10 s holds too few breaths", record.respiratoryRate)
    }

    @Test fun sixtySecondChestMeasurementSavesBreathingRate() {
        val vm = realSensorViewModel()
        vm.setTab("VIBRATION")
        vm.setMeasurementDuration(BiometricViewModel.LONG_DURATION_SEC)
        vm.startMeasurementSecured()
        feedChest(vm, seconds = 60)
        finishCountdown(BiometricViewModel.LONG_DURATION_SEC)

        val record = awaitRecords { it.isNotEmpty() }.single()
        assertEquals("VIBRATION", record.type)
        assertEquals(15.0, record.respiratoryRate!!, 1.0)
        assertNull("no HRV from the chest", record.rmssdMs)
    }

    @Test fun tremorMeasurementSavesAmplitudeAndFrequency() {
        val vm = realSensorViewModel()
        vm.setTab("TREMOR")
        vm.startMeasurementSecured()
        feedTremor(vm, seconds = BiometricViewModel.TREMOR_DURATION_SEC)
        finishCountdown(BiometricViewModel.TREMOR_DURATION_SEC)

        val record = awaitRecords { it.isNotEmpty() }.single()
        assertEquals("TREMOR", record.type)
        assertEquals("INFO", record.status)
        assertEquals("dominant frequency", 10.0, record.secondaryValue, 0.3)
        assertEquals("RMS of a 0.1 m/s² sine", 0.0707, record.value, 0.015)
    }

    @Test fun chestBreathingComesFromTheChestSensorEvenIfTheCameraIsRunning() {
        // Camera frames (breathing at 12/min) arrive during a chest measurement (breathing at 15/min):
        // the chest result must come from the accelerometer only.
        val vm = realSensorViewModel()
        vm.setTab("VIBRATION")
        vm.setMeasurementDuration(BiometricViewModel.LONG_DURATION_SEC)
        vm.startMeasurementSecured()
        feedFingertip(vm, seconds = 60)
        feedChest(vm, seconds = 60)
        finishCountdown(BiometricViewModel.LONG_DURATION_SEC)
        assertEquals(15.0, awaitRecords { it.isNotEmpty() }.single().respiratoryRate!!, 1.0)
    }

    @Test fun stoppedMeasurementSavesNothing() {
        val vm = realSensorViewModel()
        vm.setMeasurementDuration(BiometricViewModel.LONG_DURATION_SEC)
        vm.startMeasurementSecured()
        feedFingertip(vm, seconds = 30)
        vm.stopMeasurement()
        finishCountdown(BiometricViewModel.LONG_DURATION_SEC)

        assertTrue(awaitRecords(timeoutMs = 1_000) { false }.isEmpty())
    }

    @Test fun switchingTabCancelsTheMeasurement() {
        val vm = realSensorViewModel()
        vm.startMeasurementSecured()
        feedFingertip(vm, seconds = 5)
        vm.setTab("TREMOR")
        finishCountdown(BiometricViewModel.QUICK_DURATION_SEC)

        assertTrue(awaitRecords(timeoutMs = 1_000) { false }.isEmpty())
    }
}
