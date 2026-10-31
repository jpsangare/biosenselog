package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BiometricDao {
    @Query("SELECT * FROM biometric_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<BiometricRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: BiometricRecord): Long

    @Query("DELETE FROM biometric_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)

    @Query("DELETE FROM biometric_records")
    suspend fun clearAllRecords()
}
