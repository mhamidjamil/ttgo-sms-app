package com.textgate.app.data.repository

import com.textgate.app.data.firebase.FirestoreDataSource
import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.model.HistoryEntry
import com.textgate.app.domain.model.SmsJob
import com.textgate.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SmsRepositoryImpl(private val firestore: FirestoreDataSource) : SmsRepository {

    override suspend fun enqueueJob(uid: String, phoneNumber: String, message: String): Result<String> =
        firestore.enqueueJob(uid, phoneNumber, message)

    override fun getHistory(uid: String): Flow<List<HistoryEntry>> =
        firestore.getHistory(uid).map { list -> list.map { it.toDomain() } }

    override suspend fun fetchJobStatus(jobId: String): Result<SmsJob> =
        firestore.fetchJobStatus(jobId).map { it.toDomain() }

    override suspend fun updateHistoryStatus(uid: String, historyId: String, status: String): Result<Unit> =
        firestore.updateHistoryStatus(uid, historyId, status)

    override suspend fun enqueueOtpSms(uid: String, phoneNumber: String, message: String): Result<Unit> =
        firestore.enqueueOtpSms(phoneNumber, message, enqueBy = "app:$uid:otp")

    // ── Arrival monitoring (V2) ───────────────────────────────────────────────

    override suspend fun enqueueAutoArrivalSms(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
    ): Result<Unit> = firestore.enqueueAutoArrivalSms(
        uid, phoneNumber, recipientName, message, location, locationLabel, routineTriggered,
    )

    override suspend fun logAutoWhatsAppArrival(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
    ): Result<Unit> = firestore.logAutoWhatsAppArrival(
        uid, phoneNumber, recipientName, message, location, locationLabel, routineTriggered,
    )

    override suspend fun logAutoArrivalFailure(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
        error: String,
    ): Result<Unit> = firestore.logAutoArrivalFailure(
        uid, phoneNumber, recipientName, message, location, locationLabel, routineTriggered, error,
    )

    override suspend fun updateAutoHistoryStatus(uid: String, entryId: String, status: String): Result<Unit> =
        firestore.updateAutoHistoryStatus(uid, entryId, status)

    override suspend fun retryAutoArrivalSms(
        uid: String,
        entryId: String,
        phoneNumber: String,
        message: String,
    ): Result<Unit> = firestore.retryAutoArrivalSms(uid, entryId, phoneNumber, message)

    override fun getAutoHistory(uid: String): Flow<List<AutoHistoryEntry>> =
        firestore.getAutoHistory(uid).map { list -> list.map { it.toDomain() } }
}
