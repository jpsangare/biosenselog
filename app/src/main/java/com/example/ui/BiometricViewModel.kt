package com.example.ui

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.example.BuildConfig
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BiometricDatabase
import com.example.data.BiometricRecord
import com.example.data.BiometricRepository
import com.example.sensor.AccelerometerMonitor
import com.example.sensor.FingerDetectionStatus
import com.example.sensor.HrvCalculator
import com.example.sensor.HrvMetrics
import com.example.sensor.PpgBeatDetector
import com.example.sensor.RespirationDetector
import com.example.sensor.StepCounterMonitor
import com.example.sensor.TremorAnalyzer
import com.example.sensor.TremorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BiometricViewModel(application: Application) : AndroidViewModel(application) {

    private val database = BiometricDatabase.getDatabase(application)
    private val repository = BiometricRepository(database.biometricDao())

    // Database state
    val recentRecords: StateFlow<List<BiometricRecord>> = repository.allRecords
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Mode: "PPG", "VIBRATION" or "TREMOR"
    private val _currentTab = MutableStateFlow("PPG")
    val currentTab: StateFlow<String> = _currentTab.asStateFlow()

    // Today's steps from the step counter; null until it reports (or when unavailable).
    private val _todaySteps = MutableStateFlow<Long?>(null)
    val todaySteps: StateFlow<Long?> = _todaySteps.asStateFlow()
    private var stepCounterMonitor: StepCounterMonitor? = null

    // Simulator: developer builds only. Store builds always use the real sensors, so users never
    // see or save made-up readings.
    private val _isSimulatorEnabled = MutableStateFlow(SIMULATOR_ALLOWED)
    val isSimulatorEnabled: StateFlow<Boolean> = _isSimulatorEnabled.asStateFlow()

    // Measurement activity
    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    // Selected duration for PPG and chest measurements: quick 10 s, or 60 s for HRV and breathing rate.
    // The tremor test always lasts TREMOR_DURATION_SEC.
    private val _measurementDurationSec = MutableStateFlow(QUICK_DURATION_SEC)
    val measurementDurationSec: StateFlow<Int> = _measurementDurationSec.asStateFlow()

    // Length of the measurement in progress (or of the next one), drives the progress bar.
    private val _measurementTotalSec = MutableStateFlow(QUICK_DURATION_SEC)
    val measurementTotalSec: StateFlow<Int> = _measurementTotalSec.asStateFlow()

    private val _countdown = MutableStateFlow(QUICK_DURATION_SEC)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    // Live state values
    // Real-sensor mode starts empty (0 = no reading yet); the simulator starts at resting values.
    private val _liveBPM = MutableStateFlow(if (SIMULATOR_ALLOWED) 72 else 0)
    val liveBPM: StateFlow<Int> = _liveBPM.asStateFlow()

    private val _liveBloodVolume = MutableStateFlow(if (SIMULATOR_ALLOWED) 2.4 else 0.0) // Blood flow amplitude %
    val liveBloodVolume: StateFlow<Double> = _liveBloodVolume.asStateFlow()

    private val _liveVibeRMS = MutableStateFlow(if (SIMULATOR_ALLOWED) 0.12 else 0.0) // Chest vibration RMS m/s^2
    val liveVibeRMS: StateFlow<Double> = _liveVibeRMS.asStateFlow()

    // Live wave lists for drawing moving graphs
    private val _ppgWaveform = MutableStateFlow<List<Float>>(emptyList())
    val ppgWaveform: StateFlow<List<Float>> = _ppgWaveform.asStateFlow()

    private val _vibeWaveform = MutableStateFlow<List<Float>>(emptyList())
    val vibeWaveform: StateFlow<List<Float>> = _vibeWaveform.asStateFlow()

    // Sensors statuses
    private val _isFingerOnLens = MutableStateFlow(false)
    val isFingerOnLens: StateFlow<Boolean> = _isFingerOnLens.asStateFlow()

    private val _fingerStatus = MutableStateFlow(FingerDetectionStatus.NONE)
    val fingerStatus: StateFlow<FingerDetectionStatus> = _fingerStatus.asStateFlow()

    // Hardware Sensors
    private var accelerometerMonitor: AccelerometerMonitor? = null

    // Test simulation for hardware mode (e.g. testing in emulator or test suite)
    private val _isSimulatedFingerTouch = MutableStateFlow(false)
    val isSimulatedFingerTouch: StateFlow<Boolean> = _isSimulatedFingerTouch.asStateFlow()

    private var simulatedFingerJob: Job? = null
    private var fingerAbsenceCounter = 0

    // Jobs
    private var simulationJob: Job? = null
    private var measurementJob: Job? = null

    // Peak detection helpers for live camera
    private val ppgValueHistory = mutableListOf<Double>() // Continuous double precision
    private val ppgTimestampHistory = mutableListOf<Long>()
    private val maxPPGHistorySize = 90 // Approx 3 seconds at 30 fps for responsive tracking

    // Full-length recording of the current measurement (camera and accelerometer), analysed when it ends.
    // Written from sensor threads, read when saving: guarded by recordingLock.
    private val recordingLock = Any()
    private val cameraValues = mutableListOf<Double>()
    private val cameraTimes = mutableListOf<Long>()
    private val accelX = mutableListOf<Double>()
    private val accelY = mutableListOf<Double>()
    private val accelZ = mutableListOf<Double>()
    private val accelTimes = mutableListOf<Long>()
    private val maxCameraSamples = (LONG_DURATION_SEC + 10) * 30 // longest measurement at 30 fps, plus margin
    private val maxAccelSamples = (LONG_DURATION_SEC + 10) * 60  // longest measurement at up to 60 Hz

    private val bpmLock = Any()
    private var displayedBpmFloat = _liveBPM.value.toDouble()
    private var displayedVolumeFloat = _liveBloodVolume.value
    private var simulatedTargetBpm = 72.0

    // "Not a medical device" notice: must be accepted once before measuring.
    private val prefs = application.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    private val _isDisclaimerAccepted = MutableStateFlow(prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false))
    val isDisclaimerAccepted: StateFlow<Boolean> = _isDisclaimerAccepted.asStateFlow()

    fun acceptDisclaimer() {
        prefs.edit { putBoolean(KEY_DISCLAIMER_ACCEPTED, true) }
        _isDisclaimerAccepted.value = true
    }

    init {
        if (_isSimulatorEnabled.value) startSimulation() else startHardwareSensors()
    }

    fun setTab(tabName: String) {
        if (_isMeasuring.value) stopMeasurement()
        val ratesDiffer = (tabName == "TREMOR") != (_currentTab.value == "TREMOR")
        _currentTab.value = tabName
        // Tremor needs a faster accelerometer rate than the chest vibration filters are tuned for.
        if (ratesDiffer && !_isSimulatorEnabled.value) {
            stopHardwareSensors()
            startHardwareSensors()
        }
    }

    fun setMeasurementDuration(seconds: Int) {
        if (_isMeasuring.value) return
        _measurementDurationSec.value = seconds
    }

    /** Starts today's step count; call once the activity permission is granted. */
    fun startStepCounter() {
        if (stepCounterMonitor != null) return
        if (!isStepCounterAvailable) return
        stepCounterMonitor = StepCounterMonitor(getApplication()) { _todaySteps.value = it }.also { it.start() }
    }

    val isStepCounterAvailable: Boolean = StepCounterMonitor.isAvailable(application)

    fun setSimulatorEnabled(enabled: Boolean) {
        if (enabled && !SIMULATOR_ALLOWED) return
        _isSimulatorEnabled.value = enabled
        if (enabled) {
            setSimulatedFingerTouch(false)
            // Stop hardware sensors, start simulator
            stopHardwareSensors()
            startSimulation()
        } else {
            // Stop simulator, start hardware
            stopSimulation()
            startHardwareSensors()
        }
    }

    // Allows simulating realistic fingertip pulse wave directly on the real-sensor pipeline (ideal for emulator/testing)
    fun setSimulatedFingerTouch(enabled: Boolean) {
        if (enabled && !SIMULATOR_ALLOWED) return
        _isSimulatedFingerTouch.value = enabled
        simulatedFingerJob?.cancel()
        if (enabled && !_isSimulatorEnabled.value) {
            simulatedFingerJob = viewModelScope.launch {
                var simTime = 0.0
                while (_isSimulatedFingerTouch.value && !_isSimulatorEnabled.value) {
                    simTime += 0.033 // ~30 fps
                    val f = simulatedTargetBpm / 60.0
                    val pulse = 140.0 + 12.0 * sin(2.0 * Math.PI * f * simTime) +
                            3.5 * sin(4.0 * Math.PI * f * simTime + 0.8) +
                            (Math.random() - 0.5) * 0.2
                    onCameraFrameUpdated(pulse, true, FingerDetectionStatus.DETECTED)
                    delay(33)
                }
            }
        } else {
            simulatedFingerJob = null
            if (!_isFingerOnLens.value) {
                _liveBPM.value = 0
                _liveBloodVolume.value = 0.0
                _ppgWaveform.value = emptyList()
            }
        }
    }

    // Toggle simulation details or change parameters to simulate abnormal values if needed
    fun triggerAbnormalSimulate(abnormal: Boolean) {
        if (!SIMULATOR_ALLOWED) return
        val targetBpm = if (abnormal) 115 else 72
        val targetVolume = if (abnormal) 5.2 else 2.4
        val targetVibe = if (abnormal) 0.45 else 0.12

        simulatedTargetBpm = targetBpm.toDouble()

        if (_isSimulatorEnabled.value) {
            _liveBPM.value = targetBpm
            _liveBloodVolume.value = targetVolume
            _liveVibeRMS.value = targetVibe
            displayedBpmFloat = targetBpm.toDouble()
            displayedVolumeFloat = targetVolume
        } else if (_isSimulatedFingerTouch.value) {
            // Instant response in simulated hardware touch mode
            displayedBpmFloat = targetBpm.toDouble()
            displayedVolumeFloat = targetVolume
            _liveBPM.value = targetBpm
            _liveBloodVolume.value = targetVolume
            ppgValueHistory.clear()
            ppgTimestampHistory.clear()
        }
    }

    private fun startSimulation() {
        stopSimulation()
        simulationJob = viewModelScope.launch {
            var time = 0.0
            val ppgWaveList = MutableList(80) { 0f }
            val vibeWaveList = MutableList(80) { 0f }

            while (true) {
                time += 0.05 // 50ms step, ~20Hz

                val targetBpm = _liveBPM.value
                val isAbnormal = targetBpm > 100

                // 1. Generate physiological arterial pressure waveform
                // Primary heart pulse: frequency f = BPM / 60
                val fPPG = targetBpm.toDouble() / 60.0
                // Heart beat primary pulse + dicrotic notch
                val ppgY = sin(2.0 * Math.PI * fPPG * time) + 0.35 * sin(4.0 * Math.PI * fPPG * time + 0.8)
                // Add minor random noise and respiratory drift (sin wave at 0.25 Hz)
                val drift = 0.15 * sin(2.0 * Math.PI * 0.23 * time)
                val noise = (Math.random() - 0.5) * 0.05
                val finalPpgValue = (ppgY + drift + noise).toFloat()

                ppgWaveList.add(finalPpgValue)
                if (ppgWaveList.size > 80) ppgWaveList.removeAt(0)
                _ppgWaveform.value = ppgWaveList.toList()

                // 2. Generate simulated Chest Vibration waveforms (Micro-Seismography)
                // SCG shows small rapid heart-thump vibrations at each heartbeat
                val heartbeatImpact = if ((time * fPPG) % 1.0 < 0.15) {
                    // Quick vibration burst
                    0.25f * sin(2.0 * Math.PI * 18.0 * (time % (1.0 / fPPG))).toFloat()
                } else {
                    0f
                }
                // Breathing lung ribs movement
                val lungMovement = 0.08f * sin(2.0 * Math.PI * 0.25 * time).toFloat()
                // Random body tremor noise
                val tremor = (Math.random() - 0.5f).toFloat() * (if (isAbnormal) 0.15f else 0.03f)
                val finalVibeValue = (heartbeatImpact + lungMovement + tremor)

                vibeWaveList.add(finalVibeValue)
                if (vibeWaveList.size > 80) vibeWaveList.removeAt(0)
                _vibeWaveform.value = vibeWaveList.toList()

                // Periodic slight fluctuation of resting metrics
                if (!isMeasuring.value) {
                    if (!isAbnormal) {
                        _liveBPM.value = 68 + (sin(time * 0.1) * 3).toInt()
                        _liveBloodVolume.value = 2.2 + sin(time * 0.08) * 0.3
                        _liveVibeRMS.value = 0.11 + cos(time * 0.15) * 0.02
                    } else {
                        _liveBPM.value = 112 + (sin(time * 0.1) * 2).toInt()
                        _liveBloodVolume.value = 4.8 + sin(time * 0.08) * 0.4
                        _liveVibeRMS.value = 0.42 + cos(time * 0.15) * 0.04
                    }
                }

                delay(50)
            }
        }
    }

    /**
     * UI tests only: stops the simulator's 20-per-second live updates (keeping the current values)
     * so the screen can become idle between test steps. Measurements still work.
     */
    @VisibleForTesting
    fun freezeSimulationForTests() = stopSimulation()

    private fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    private fun startHardwareSensors() {
        // Start Accelerometer listener
        val rate = if (_currentTab.value == "TREMOR") SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
        accelerometerMonitor = AccelerometerMonitor(
            getApplication(),
            samplingPeriod = rate,
            onRawSample = ::onAccelerometerSample
        ) { rms, waveform ->
            if (!_isSimulatorEnabled.value) {
                _liveVibeRMS.value = rms
                _vibeWaveform.value = waveform
            }
        }
        accelerometerMonitor?.start()
    }

    private fun stopHardwareSensors() {
        accelerometerMonitor?.stop()
        accelerometerMonitor = null
    }

    /** Raw accelerometer sample from AccelerometerMonitor (tests call it directly with synthetic data). */
    @VisibleForTesting
    internal fun onAccelerometerSample(x: Float, y: Float, z: Float, timestampMs: Long) {
        if (!_isMeasuring.value || _currentTab.value == "PPG") return
        synchronized(recordingLock) {
            if (accelTimes.size < maxAccelSamples) {
                accelX.add(x.toDouble())
                accelY.add(y.toDouble())
                accelZ.add(z.toDouble())
                accelTimes.add(timestampMs)
            }
        }
    }

    // Capture incoming camera pixel analysis
    fun onCameraFrameUpdated(
        luminance: Double,
        isFingerPresent: Boolean,
        status: FingerDetectionStatus = FingerDetectionStatus.NONE,
        currentTime: Long = System.currentTimeMillis() // capture time; tests pass synthetic timestamps
    ) {
        if (_isSimulatorEnabled.value) return // Ignore if simulator is running

        _fingerStatus.value = status

        // If finger is present, process raw signals
        if (isFingerPresent) {
            fingerAbsenceCounter = 0
            _isFingerOnLens.value = true

            // Store high-precision double histories for peak calculations
            ppgValueHistory.add(luminance)
            ppgTimestampHistory.add(currentTime)

            if (ppgValueHistory.size > maxPPGHistorySize) {
                ppgValueHistory.removeAt(0)
                ppgTimestampHistory.removeAt(0)
            }

            if (_isMeasuring.value) {
                synchronized(recordingLock) {
                    if (cameraValues.size < maxCameraSamples) {
                        cameraValues.add(luminance)
                        cameraTimes.add(currentTime)
                    }
                }
            }

            // Expose detrended and smoothed AC values to the live waveform plot
            val baseline = ppgValueHistory.average()
            val rawAc = ppgValueHistory.map { (it - baseline).toFloat() }
            _ppgWaveform.value = smoothSignal(rawAc)

            // Calculate heart rate as soon as sufficient historical data (~25 frames, ~0.8s) is captured
            if (ppgValueHistory.size >= 25) {
                calculateBpmFromHistory()
            }
        } else {
            // Debounce finger absence: do not wipe on momentary 1-10 frame drops
            fingerAbsenceCounter++
            if (fingerAbsenceCounter > 15) { // ~500ms of sustained absence
                _isFingerOnLens.value = false
                _liveBPM.value = 0
                _liveBloodVolume.value = 0.0
                _ppgWaveform.value = emptyList()
                displayedBpmFloat = 0.0
                displayedVolumeFloat = 0.0
                ppgValueHistory.clear()
                ppgTimestampHistory.clear()
            }
        }
    }

    private fun smoothSignal(input: List<Float>): List<Float> {
        if (input.size < 3) return input
        val result = FloatArray(input.size)
        for (i in input.indices) {
            val start = maxOf(0, i - 1)
            val end = minOf(input.size - 1, i + 1)
            var sum = 0f
            for (j in start..end) sum += input[j]
            result[i] = sum / (end - start + 1)
        }
        return result.toList()
    }

    private fun calculateBpmFromHistory() {
        val analysis = PpgBeatDetector.analyze(ppgValueHistory.toList(), ppgTimestampHistory.toList()) ?: return
        val instantBpm = analysis.bpm

        synchronized(bpmLock) {
            // Adaptive display filter: fast response on step changes, stability on minor noise
            if (_liveBPM.value <= 35) {
                displayedBpmFloat = instantBpm
                _liveBPM.value = instantBpm.toInt()
            } else {
                val diff = instantBpm - displayedBpmFloat
                val alpha = if (kotlin.math.abs(diff) > 7.0) 0.50 else 0.22
                displayedBpmFloat += alpha * diff
                _liveBPM.value = kotlin.math.round(displayedBpmFloat).toInt()
            }

            // Smooth perfusion index / volume amplitude
            displayedVolumeFloat += 0.25 * (analysis.perfusionPercent - displayedVolumeFloat)
            _liveBloodVolume.value = kotlin.math.round(displayedVolumeFloat * 10.0) / 10.0
        }
    }

    // Start 10 seconds measurement test
    fun startMeasurementSecured() {
        if (_isMeasuring.value) return
        clearRecording()
        val duration = if (_currentTab.value == "TREMOR") TREMOR_DURATION_SEC else _measurementDurationSec.value
        _measurementTotalSec.value = duration
        _isMeasuring.value = true
        _countdown.value = duration

        measurementJob = viewModelScope.launch {
            while (_countdown.value > 0) {
                delay(1000)
                _countdown.value -= 1

                // Simulate slight dynamic physiological changes during reading
                if (_isSimulatorEnabled.value) {
                    val currentBpm = _liveBPM.value
                    if (currentBpm in 50..130) {
                        _liveBPM.value = currentBpm + (-1..1).random()
                    }
                }
            }

            // Countdown done! Stop measurement and auto-save current stable readings to DB
            _isMeasuring.value = false
            saveReading(takeRecording(duration))
        }
    }

    fun stopMeasurement() {
        measurementJob?.cancel()
        measurementJob = null
        _isMeasuring.value = false
        clearRecording()
    }

    private fun clearRecording() = synchronized(recordingLock) {
        cameraValues.clear()
        cameraTimes.clear()
        accelX.clear()
        accelY.clear()
        accelZ.clear()
        accelTimes.clear()
    }

    /** Sensor data of a finished measurement (empty lists in the simulator). */
    private class Recording(
        val durationSec: Int,
        val camera: List<Double>,
        val cameraTimes: List<Long>,
        val accel: List<List<Double>>,
        val accelTimes: List<Long>
    )

    private fun takeRecording(durationSec: Int): Recording = synchronized(recordingLock) {
        Recording(
            durationSec,
            cameraValues.toList(), cameraTimes.toList(),
            listOf(accelX.toList(), accelY.toList(), accelZ.toList()), accelTimes.toList()
        )
    }.also { clearRecording() }

    private val simulatedAbnormal: Boolean get() = simulatedTargetBpm > 100

    /** HRV from the camera recording, or plausible simulated values (lower when the heart rate is high). */
    private fun measurementHrv(recording: Recording?): HrvMetrics? {
        if (recording == null) return null
        if (_isSimulatorEnabled.value) {
            val rmssd = if (simulatedAbnormal) 12.0 + Math.random() * 8.0 else 30.0 + Math.random() * 20.0
            return HrvMetrics(rmssd, rmssd * (1.1 + Math.random() * 0.3), 0)
        }
        return HrvCalculator.compute(PpgBeatDetector.beatIntervalsInWindows(recording.camera, recording.cameraTimes))
    }

    /** Breathing rate of a long measurement, from the finger camera or the chest accelerometer. */
    private fun measurementBreathing(recording: Recording?, type: String): Double? {
        if (recording == null || recording.durationSec < LONG_DURATION_SEC) return null
        if (_isSimulatorEnabled.value) {
            return if (simulatedAbnormal) 22.0 + Math.random() * 4.0 else 12.0 + Math.random() * 6.0
        }
        return if (type == "PPG") RespirationDetector.estimate(recording.camera, recording.cameraTimes)
        else RespirationDetector.estimateFromAxes(recording.accel, recording.accelTimes)
    }

    private fun measurementTremor(recording: Recording?): TremorResult? {
        if (recording == null) return null
        if (_isSimulatorEnabled.value) {
            return if (simulatedAbnormal) TremorResult(0.35 + Math.random() * 0.15, 4.5 + Math.random())
            else TremorResult(0.04 + Math.random() * 0.04, 8.5 + Math.random() * 3.0)
        }
        val (x, y, z) = recording.accel
        return TremorAnalyzer.analyze(x, y, z, recording.accelTimes)
    }

    /** Save button: stores the live reading now. Recording-based metrics need a full measurement. */
    fun logCurrentReadingToDb() = saveReading(null)

    // Save actual/simulated readings directly into SQLite room database
    private fun saveReading(recording: Recording?) {
        viewModelScope.launch {
            val type = _currentTab.value
            val record = when (type) {
                "PPG" -> {
                    val bpm = _liveBPM.value.toDouble()
                    if (bpm <= 35.0) {
                        Log.d("BiometricViewModel", "Skipping log: No valid heart rate pulse recorded.")
                        return@launch
                    }
                    val (hrv, breathing) = withContext(Dispatchers.Default) {
                        measurementHrv(recording) to measurementBreathing(recording, type)
                    }
                    BiometricRecord(
                        type = type,
                        value = bpm,
                        secondaryValue = _liveBloodVolume.value,
                        status = when {
                            bpm < 60.0 -> "LOW"
                            bpm <= 100.0 -> "NORMAL"
                            else -> "HIGH"
                        },
                        rmssdMs = hrv?.rmssdMs,
                        sdnnMs = hrv?.sdnnMs,
                        respiratoryRate = breathing
                    )
                }
                "TREMOR" -> {
                    val tremor = withContext(Dispatchers.Default) { measurementTremor(recording) }
                    if (tremor == null) {
                        Log.d("BiometricViewModel", "Skipping log: tremor needs a full, steady measurement.")
                        return@launch
                    }
                    // No validated normal range for phone-measured tremor: stored as information only.
                    BiometricRecord(type = type, value = tremor.amplitudeRms, secondaryValue = tremor.dominantHz, status = "INFO")
                }
                else -> {
                    val vibe = _liveVibeRMS.value
                    val breathing = withContext(Dispatchers.Default) { measurementBreathing(recording, type) }
                    BiometricRecord(
                        type = type,
                        value = vibe,
                        secondaryValue = 0.0,
                        status = when {
                            vibe < 0.05 -> "LOW"
                            vibe <= 0.25 -> "NORMAL"
                            else -> "HIGH"
                        },
                        respiratoryRate = breathing
                    )
                }
            }
            repository.insertRecord(record)
            // Health values are never written to the system log in store builds.
            if (BuildConfig.DEBUG) Log.d("BiometricViewModel", "Logged Biometric record to database: $record")
        }
    }

    fun deleteRecord(record: BiometricRecord) {
        viewModelScope.launch {
            repository.deleteRecordById(record.id)
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            repository.clearAllRecords()
        }
    }

    fun generateCsvExport(records: List<BiometricRecord>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        fun num(v: Double?, pattern: String) = v?.let { String.format(Locale.US, pattern, it) } ?: ""
        val sb = StringBuilder()
        sb.append("ID,Timestamp,Date Time,Sensor Type,Value,Unit,Secondary Value,Status,HRV RMSSD (ms),HRV SDNN (ms),Breathing Rate (breaths/min),Notes\n")
        records.forEach { record ->
            val dateStr = sdf.format(Date(record.timestamp))
            val (typeLabel, primaryVal, unit, secondaryVal) = when (record.type) {
                "PPG" -> listOf("Blood Flow (PPG)", "${record.value.toInt()}", "BPM", String.format(Locale.US, "%.1f%%", record.secondaryValue))
                "TREMOR" -> listOf("Hand Tremor", num(record.value, "%.3f"), "m/s²", String.format(Locale.US, "%.1f Hz", record.secondaryValue))
                else -> listOf("Chest Vibration (SCG)", num(record.value, "%.3f"), "m/s²", "N/A")
            }
            val notesEscaped = record.notes.replace("\"", "\"\"")
            sb.append("${record.id},${record.timestamp},\"$dateStr\",\"$typeLabel\",$primaryVal,\"$unit\",\"$secondaryVal\",${record.status}," +
                "${num(record.rmssdMs, "%.1f")},${num(record.sdnnMs, "%.1f")},${num(record.respiratoryRate, "%.1f")},\"$notesEscaped\"\n")
        }
        return sb.toString()
    }

    fun generateReportExport(records: List<BiometricRecord>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        fun appendBreathing(group: List<BiometricRecord>) {
            val rates = group.mapNotNull { it.respiratoryRate }
            if (rates.isNotEmpty()) {
                sb.append(String.format(Locale.getDefault(), "Average breathing rate: %.0f breaths/min (%d measurements)\n",
                    rates.average(), rates.size))
            }
        }
        sb.append("========================================\n")
        sb.append("     BIOSENSE LOG - BIOMETRIC REPORT\n")
        sb.append("========================================\n")
        sb.append("Export date: ${sdf.format(Date())}\n")
        sb.append("Total measurements: ${records.size}\n\n")

        val ppgRecords = records.filter { it.type == "PPG" }
        if (ppgRecords.isNotEmpty()) {
            val avgBpm = ppgRecords.map { it.value }.average()
            val minBpm = ppgRecords.minOf { it.value }
            val maxBpm = ppgRecords.maxOf { it.value }
            val avgVolume = ppgRecords.map { it.secondaryValue }.average()
            sb.append("--- BLOOD FLOW (PPG) ---\n")
            sb.append("Measurements: ${ppgRecords.size}\n")
            sb.append("Average pulse: ${avgBpm.toInt()} BPM (Min: ${minBpm.toInt()}, Max: ${maxBpm.toInt()})\n")
            sb.append(String.format(Locale.getDefault(), "Average perfusion index: %.1f%%\n", avgVolume))
            val rmssdValues = ppgRecords.mapNotNull { it.rmssdMs }
            if (rmssdValues.isNotEmpty()) {
                sb.append(String.format(Locale.getDefault(), "Average HRV (RMSSD): %.0f ms (%d measurements)\n",
                    rmssdValues.average(), rmssdValues.size))
            }
            appendBreathing(ppgRecords)
            sb.append("\n")
        }

        val vibeRecords = records.filter { it.type == "VIBRATION" }
        if (vibeRecords.isNotEmpty()) {
            val avgVibe = vibeRecords.map { it.value }.average()
            val minVibe = vibeRecords.minOf { it.value }
            val maxVibe = vibeRecords.maxOf { it.value }
            sb.append("--- CHEST VIBRATION (SCG) ---\n")
            sb.append("Measurements: ${vibeRecords.size}\n")
            sb.append(String.format(Locale.getDefault(), "Average RMS vibration: %.3f m/s² (Min: %.3f, Max: %.3f)\n", avgVibe, minVibe, maxVibe))
            appendBreathing(vibeRecords)
            sb.append("\n")
        }

        val tremorRecords = records.filter { it.type == "TREMOR" }
        if (tremorRecords.isNotEmpty()) {
            sb.append("--- HAND TREMOR ---\n")
            sb.append("Measurements: ${tremorRecords.size}\n")
            sb.append(String.format(Locale.getDefault(), "Average amplitude: %.3f m/s² | Average frequency: %.1f Hz\n\n",
                tremorRecords.map { it.value }.average(), tremorRecords.map { it.secondaryValue }.average()))
        }

        sb.append("--- DETAILED HISTORY ---\n")
        records.forEachIndexed { index, record ->
            val dateStr = sdf.format(Date(record.timestamp))
            val breathText = record.respiratoryRate?.let { String.format(Locale.getDefault(), " | Breathing: %.0f /min", it) } ?: ""
            when (record.type) {
                "PPG" -> {
                    val hrvText = record.rmssdMs?.let { String.format(Locale.getDefault(), " | HRV: %.0f ms", it) } ?: ""
                    sb.append(String.format(Locale.getDefault(), "[%d] %s | Pulse: %d BPM (Vol: %.1f%%)%s%s | Status: %s\n",
                        index + 1, dateStr, record.value.toInt(), record.secondaryValue, hrvText, breathText, record.status))
                }
                "TREMOR" -> sb.append(String.format(Locale.getDefault(), "[%d] %s | Tremor: %.3f m/s² at %.1f Hz\n",
                    index + 1, dateStr, record.value, record.secondaryValue))
                else -> sb.append(String.format(Locale.getDefault(), "[%d] %s | Vibration: %.3f m/s²%s | Status: %s\n",
                    index + 1, dateStr, record.value, breathText, record.status))
            }
        }
        sb.append("========================================\n")
        return sb.toString()
    }

    companion object {
        const val QUICK_DURATION_SEC = 10
        const val LONG_DURATION_SEC = 60
        const val TREMOR_DURATION_SEC = 15

        /** Simulated data is a development aid; it is compiled out of store (release) builds. */
        val SIMULATOR_ALLOWED: Boolean = BuildConfig.DEBUG
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted_v2" // v2: welcome notice gained privacy and free-app sections
    }

    override fun onCleared() {
        super.onCleared()
        simulatedFingerJob?.cancel()
        simulatedFingerJob = null
        stopSimulation()
        stopHardwareSensors()
        stepCounterMonitor?.stop()
        stepCounterMonitor = null
    }
}
