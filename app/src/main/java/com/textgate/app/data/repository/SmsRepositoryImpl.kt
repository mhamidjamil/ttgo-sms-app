package com.textgate.app.data.repository

import com.textgate.app.data.firebase.FirestoreDataSource
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

    override suspend fun fetchJobStatus(phoneNumber: String): Result<SmsJob> =
        firestore.fetchJobStatus(phoneNumber).map { it.toDomain() }

    override suspend fun updateHistoryStatus(uid: String, historyId: String, status: String): Result<Unit> =
        firestore.updateHistoryStatus(uid, historyId, status)

    override suspend fun enqueueOtpSms(uid: String, phoneNumber: String, message: String): Result<Unit> =
        firestore.enqueueOtpSms(phoneNumber, message, enqueBy = "app:$uid:otp")
}
