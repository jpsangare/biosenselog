package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BiometricDatabase
import com.example.data.BiometricRecord
import com.example.data.BiometricRepository
import com.example.sensor.AccelerometerMonitor
import com.example.sensor.FingerDetectionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // Mode: "PPG" or "VIBRATION"
    private val _currentTab = MutableStateFlow("PPG")
    val currentTab: StateFlow<String> = _currentTab.asStateFlow()

    // Simulator Toggle
    private val _isSimulatorEnabled = MutableStateFlow(true)
    val isSimulatorEnabled: StateFlow<Boolean> = _isSimulatorEnabled.asStateFlow()

    // Measurement activity
    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    private val _countdown = MutableStateFlow(10) // 10 seconds test
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    // Live state values
    private val _liveBPM = MutableStateFlow(72)
    val liveBPM: StateFlow<Int> = _liveBPM.asStateFlow()

    private val _liveBloodVolume = MutableStateFlow(2.4) // Blood flow amplitude %
    val liveBloodVolume: StateFlow<Double> = _liveBloodVolume.asStateFlow()

    private val _liveVibeRMS = MutableStateFlow(0.12) // Chest vibration RMS m/s^2
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

    private val bpmLock = Any()
    private var displayedBpmFloat = 72.0
    private var displayedVolumeFloat = 2.4
    private var simulatedTargetBpm = 72.0

    init {
        // Start simulation by default so the preview or streaming emulator immediately displays active waveforms
        startSimulation()
    }

    fun setTab(tabName: String) {
        _currentTab.value = tabName
    }

    fun setSimulatorEnabled(enabled: Boolean) {
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

    private fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    private fun startHardwareSensors() {
        // Start Accelerometer listener
        accelerometerMonitor = AccelerometerMonitor(getApplication()) { rms, waveform ->
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

    // Capture incoming camera pixel analysis
    fun onCameraFrameUpdated(
        luminance: Double,
        isFingerPresent: Boolean,
        status: FingerDetectionStatus = FingerDetectionStatus.NONE
    ) {
        if (_isSimulatorEnabled.value) return // Ignore if simulator is running

        val currentTime = System.currentTimeMillis()
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
        val y = ppgValueHistory.toList()
        val t = ppgTimestampHistory.toList()
        if (y.size < 12) return

        // 1. Moving baseline subtraction (detrending) to eliminate slow respiratory drift
        val windowSize = minOf(16, y.size / 2)
        val detrended = mutableListOf<Double>()
        for (i in y.indices) {
            val start = maxOf(0, i - windowSize / 2)
            val end = minOf(y.size - 1, i + windowSize / 2)
            var sum = 0.0
            for (j in start..end) sum += y[j]
            val localBase = sum / (end - start + 1)
            detrended.add(y[i] - localBase)
        }

        // 2. 3-point binomial smoothing [0.25, 0.5, 0.25] to filter out sensor high-frequency noise
        val smoothed = mutableListOf<Double>()
        for (i in detrended.indices) {
            val prev = if (i > 0) detrended[i - 1] else detrended[i]
            val curr = detrended[i]
            val next = if (i < detrended.size - 1) detrended[i + 1] else detrended[i]
            smoothed.add(0.25 * prev + 0.50 * curr + 0.25 * next)
        }

        val maxL = smoothed.maxOrNull() ?: 0.0
        val minL = smoothed.minOrNull() ?: 0.0
        val peakToPeak = maxL - minL

        // Only search for peaks if signal has real physiological pulse variation
        if (peakToPeak >= 0.35) {
            // Systolic peak threshold: 50% of peak height
            // This strictly rejects the secondary dicrotic wave (typically 30-40% height)
            val peakThreshold = maxOf(0.20, maxL * 0.50)
            val rawPeaks = mutableListOf<Int>()
            for (i in 2 until smoothed.size - 2) {
                val center = smoothed[i]
                if (center > peakThreshold &&
                    center >= smoothed[i - 1] && center >= smoothed[i - 2] &&
                    center >= smoothed[i + 1] && center >= smoothed[i + 2]
                ) {
                    rawPeaks.add(i)
                }
            }

            // 3. Sub-frame parabolic interpolation for exact peak timestamps
            val peakExactTimestamps = mutableListOf<Double>()
            for (pIdx in rawPeaks) {
                val y1 = smoothed[pIdx - 1]
                val y2 = smoothed[pIdx]
                val y3 = smoothed[pIdx + 1]
                val denom = 2.0 * (y1 - 2.0 * y2 + y3)
                val delta = if (denom != 0.0) ((y1 - y3) / denom).coerceIn(-0.5, 0.5) else 0.0
                val dt = if (pIdx > 0) (t[pIdx] - t[pIdx - 1]).toDouble() else 33.3
                val exactTime = t[pIdx].toDouble() + delta * dt

                if (peakExactTimestamps.isEmpty()) {
                    peakExactTimestamps.add(exactTime)
                } else {
                    val lastTime = peakExactTimestamps.last()
                    val gap = exactTime - lastTime
                    // Minimum refractory period 360ms (max ~166 BPM) to block any dicrotic notch
                    if (gap >= 360.0) {
                        peakExactTimestamps.add(exactTime)
                    }
                }
            }

            // 4. Extract consecutive beat-to-beat intervals
            if (peakExactTimestamps.size >= 2) {
                val intervals = mutableListOf<Double>()
                for (p in 0 until peakExactTimestamps.size - 1) {
                    val gap = peakExactTimestamps[p + 1] - peakExactTimestamps[p]
                    // Physiological pulse interval limits: 375ms (160 BPM) to 1350ms (44 BPM)
                    if (gap in 375.0..1350.0) {
                        intervals.add(gap)
                    }
                }

                if (intervals.isNotEmpty()) {
                    // Recency-weighted average of the most recent intervals:
                    // The latest beat carries up to 50% weight, so changes in pulse rate
                    // are detected promptly (within 1-2 beats) without sluggish lag!
                    val recentCount = minOf(4, intervals.size)
                    val subList = intervals.takeLast(recentCount)
                    val weights = when (recentCount) {
                        1 -> listOf(1.0)
                        2 -> listOf(0.35, 0.65)
                        3 -> listOf(0.20, 0.30, 0.50)
                        else -> listOf(0.10, 0.20, 0.30, 0.40)
                    }
                    var weightedSum = 0.0
                    var weightSum = 0.0
                    for (k in subList.indices) {
                        weightedSum += subList[k] * weights[k]
                        weightSum += weights[k]
                    }
                    val weightedGap = weightedSum / weightSum
                    val instantBpm = 60000.0 / weightedGap

                    if (instantBpm in 44.0..160.0) {
                        synchronized(bpmLock) {
                            // Adaptive display filter:
                            // Fast response on step changes, silky stability on minor noise
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
                            val meanVal = y.average().coerceAtLeast(1.0)
                            val rawAmp = (peakToPeak / meanVal) * 100.0
                            val clampedAmp = rawAmp.coerceIn(0.8, 6.0)
                            displayedVolumeFloat += 0.25 * (clampedAmp - displayedVolumeFloat)
                            _liveBloodVolume.value = kotlin.math.round(displayedVolumeFloat * 10.0) / 10.0
                        }
                    }
                }
            }
        }
    }

    // Start 10 seconds measurement test
    fun startMeasurementSecured() {
        if (_isMeasuring.value) return
        _isMeasuring.value = true
        _countdown.value = 10

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
            logCurrentReadingToDb()
        }
    }

    fun stopMeasurement() {
        measurementJob?.cancel()
        measurementJob = null
        _isMeasuring.value = false
    }

    // Save actual/simulated readings directly into SQLite room database
    fun logCurrentReadingToDb() {
        viewModelScope.launch {
            val type = _currentTab.value // "PPG" or "VIBRATION"
            val value: Double
            val secondaryValue: Double
            val status: String

            if (type == "PPG") {
                val bpm = _liveBPM.value.toDouble()
                if (bpm <= 35.0) {
                    Log.d("BiometricViewModel", "Skipping log: No valid heart rate pulse recorded.")
                    return@launch
                }
                value = bpm
                secondaryValue = _liveBloodVolume.value

                // Determine normal limit status
                status = when {
                    bpm < 60.0 -> "LOW"
                    bpm <= 100.0 -> "NORMAL"
                    else -> "HIGH"
                }
            } else {
                val vibe = _liveVibeRMS.value
                value = vibe
                secondaryValue = 0.0

                // Determine normal chest vibe limits
                status = when {
                    vibe < 0.05 -> "LOW"
                    vibe <= 0.25 -> "NORMAL"
                    else -> "HIGH"
                }
            }

            // Save
            val record = BiometricRecord(
                type = type,
                value = value,
                secondaryValue = secondaryValue,
                status = status,
                timestamp = System.currentTimeMillis()
            )
            repository.insertRecord(record)
            Log.d("BiometricViewModel", "Logged Biometric record to database: $record")
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
        val sb = StringBuilder()
        sb.append("ID,Timestamp,Date Time,Sensor Type,Value,Unit,Secondary Value,Status,Notes\n")
        records.forEach { record ->
            val dateStr = sdf.format(Date(record.timestamp))
            val isPpg = record.type == "BLOOD_FLOW"
            val typeLabel = if (isPpg) "Blood Flow (PPG)" else "Chest Vibration (SCG)"
            val primaryVal = if (isPpg) "${record.value.toInt()}" else String.format(Locale.US, "%.3f", record.value)
            val unit = if (isPpg) "BPM" else "m/s²"
            val secondaryVal = if (isPpg) String.format(Locale.US, "%.1f%%", record.secondaryValue) else "N/A"
            val notesEscaped = record.notes.replace("\"", "\"\"")
            sb.append("${record.id},${record.timestamp},\"$dateStr\",\"$typeLabel\",$primaryVal,\"$unit\",\"$secondaryVal\",${record.status},\"$notesEscaped\"\n")
        }
        return sb.toString()
    }

    fun generateReportExport(records: List<BiometricRecord>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("     BIOSENSE LOG - RAPPORT BIOMETRIQUE\n")
        sb.append("========================================\n")
        sb.append("Date d'export : ${sdf.format(Date())}\n")
        sb.append("Nombre total de mesures : ${records.size}\n\n")

        val ppgRecords = records.filter { it.type == "BLOOD_FLOW" }
        if (ppgRecords.isNotEmpty()) {
            val avgBpm = ppgRecords.map { it.value }.average()
            val minBpm = ppgRecords.minOf { it.value }
            val maxBpm = ppgRecords.maxOf { it.value }
            val avgVolume = ppgRecords.map { it.secondaryValue }.average()
            sb.append("--- FLUX SANGUIN (PPG) ---\n")
            sb.append("Mesures : ${ppgRecords.size}\n")
            sb.append("Pouls moyen : ${avgBpm.toInt()} BPM (Min: ${minBpm.toInt()}, Max: ${maxBpm.toInt()})\n")
            sb.append(String.format(Locale.getDefault(), "Indice perfusion moyen : %.1f%%\n\n", avgVolume))
        }

        val vibeRecords = records.filter { it.type != "BLOOD_FLOW" }
        if (vibeRecords.isNotEmpty()) {
            val avgVibe = vibeRecords.map { it.value }.average()
            val minVibe = vibeRecords.minOf { it.value }
            val maxVibe = vibeRecords.maxOf { it.value }
            sb.append("--- VIBRATIONS THORACIQUES (SCG) ---\n")
            sb.append("Mesures : ${vibeRecords.size}\n")
            sb.append(String.format(Locale.getDefault(), "Vibration RMS moyenne : %.3f m/s² (Min: %.3f, Max: %.3f)\n\n", avgVibe, minVibe, maxVibe))
        }

        sb.append("--- HISTORIQUE DÉTAILLÉ ---\n")
        records.forEachIndexed { index, record ->
            val dateStr = sdf.format(Date(record.timestamp))
            val isPpg = record.type == "BLOOD_FLOW"
            if (isPpg) {
                sb.append(String.format(Locale.getDefault(), "[%d] %s | Pouls: %d BPM (Vol: %.1f%%) | État: %s\n",
                    index + 1, dateStr, record.value.toInt(), record.secondaryValue, record.status))
            } else {
                sb.append(String.format(Locale.getDefault(), "[%d] %s | Vibration: %.3f m/s² | État: %s\n",
                    index + 1, dateStr, record.value, record.status))
            }
        }
        sb.append("========================================\n")
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        simulatedFingerJob?.cancel()
        simulatedFingerJob = null
        stopSimulation()
        stopHardwareSensors()
    }
}
