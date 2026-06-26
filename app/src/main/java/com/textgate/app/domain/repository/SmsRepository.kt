package com.textgate.app.domain.repository

import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.model.HistoryEntry
import com.textgate.app.domain.model.SmsJob
import kotlinx.coroutines.flow.Flow

interface SmsRepository {
    suspend fun enqueueJob(uid: String, phoneNumber: String, message: String): Result<String>
    fun getHistory(uid: String): Flow<List<HistoryEntry>>
    suspend fun fetchJobStatus(phoneNumber: String): Result<SmsJob>
    suspend fun updateHistoryStatus(uid: String, historyId: String, status: String): Result<Unit>
    // Enqueues an OTP verification SMS without touching user quota or history
    suspend fun enqueueOtpSms(uid: String, phoneNumber: String, message: String): Result<Unit>

    // Arrival monitoring (V2)
    suspend fun logAutoWhatsAppArrival(
        uid: String,
        phoneNumber: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
    ): Result<Unit>
    suspend fun updateAutoHistoryStatus(uid: String, entryId: String, status: String): Result<Unit>
    suspend fun retryAutoArrivalSms(
        uid: String,
        entryId: String,
        phoneNumber: String,
        message: String,
    ): Result<Unit>
    suspend fun enqueueAutoArrivalSms(
        uid: String,
        phoneNumber: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
    ): Result<Unit>
    fun getAutoHistory(uid: String): Flow<List<AutoHistoryEntry>>
}
