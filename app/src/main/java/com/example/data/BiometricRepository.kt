package com.example.data

import kotlinx.coroutines.flow.Flow

class BiometricRepository(private val biometricDao: BiometricDao) {
    val allRecords: Flow<List<BiometricRecord>> = biometricDao.getAllRecords()

    suspend fun insertRecord(record: BiometricRecord): Long {
        return biometricDao.insertRecord(record)
    }

    suspend fun deleteRecordById(id: Long) {
        biometricDao.deleteRecordById(id)
    }

    suspend fun clearAllRecords() {
        biometricDao.clearAllRecords()
    }
}
