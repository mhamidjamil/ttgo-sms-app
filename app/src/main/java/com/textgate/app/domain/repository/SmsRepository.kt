package com.textgate.app.domain.repository

import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.model.EnqueueResult
import com.textgate.app.domain.model.HistoryEntry
import com.textgate.app.domain.model.SmsJob
import kotlinx.coroutines.flow.Flow

interface SmsRepository {
    suspend fun enqueueJob(uid: String, phoneNumber: String, message: String): Result<String>
    fun getHistory(uid: String): Flow<List<HistoryEntry>>
    suspend fun fetchJobStatus(jobId: String): Result<SmsJob>
    suspend fun updateHistoryStatus(uid: String, historyId: String, status: String): Result<Unit>
    // Enqueues an OTP verification SMS without touching user quota or history
    suspend fun enqueueOtpSms(uid: String, phoneNumber: String, message: String): Result<Unit>

    // Arrival monitoring (V2). detectedAt is when the visit began, which the
    // history row keeps separately because sent_at is minutes later.
    //
    // detectionMethod, wifiMatch and the place's circle say how the arrival was
    // established: a wrong alert cannot be told apart from a wrong fence
    // afterwards unless the row records which of the two decided it. The
    // defaults describe what the WiFi engine has always done.
    suspend fun logAutoWhatsAppArrival(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
        detectedAt: Long,
        detectionMethod: String = "wifi",
        wifiMatch: Boolean = true,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        radiusMeters: Int = 0,
    ): Result<Unit>
    suspend fun logAutoArrivalFailure(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
        detectedAt: Long,
        error: String,
        detectionMethod: String = "wifi",
        wifiMatch: Boolean = true,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        radiusMeters: Int = 0,
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
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
        detectedAt: Long,
        detectionMethod: String = "wifi",
        wifiMatch: Boolean = true,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        radiusMeters: Int = 0,
    ): Result<EnqueueResult>
    fun getAutoHistory(uid: String): Flow<List<AutoHistoryEntry>>
}
