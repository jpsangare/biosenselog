package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "biometric_records")
data class BiometricRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,          // "BLOOD_FLOW" or "CHEST_VIBRATION"
    val value: Double,         // e.g., Heart rate in BPM, or vibration power in m/s^2
    val secondaryValue: Double, // e.g., blood flow volume %
    val status: String,        // "LOW", "NORMAL", "HIGH"
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = ""
)
