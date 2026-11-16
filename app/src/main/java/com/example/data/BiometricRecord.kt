package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "biometric_records")
data class BiometricRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,          // "PPG", "VIBRATION" or "TREMOR" (the dashboard tab that recorded it)
    val value: Double,         // Heart rate in BPM, vibration RMS in m/s², or tremor RMS in m/s²
    val secondaryValue: Double, // Blood flow volume % (PPG), tremor dominant frequency Hz (TREMOR)
    val status: String,        // "LOW", "NORMAL", "HIGH", or "INFO" when there is no reference range
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = "",
    val rmssdMs: Double? = null, // Heart rate variability (PPG only), null when not measurable
    val sdnnMs: Double? = null,
    val respiratoryRate: Double? = null // Breaths per minute (PPG / VIBRATION, 60 s measurements)
)
