package com.textgate.app.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.core.utils.Paths
import com.textgate.app.data.model.HistoryEntryDto
import com.textgate.app.data.model.SmsJobDto
import com.textgate.app.data.model.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreDataSource(private val db: FirebaseFirestore) {

    // ── User ─────────────────────────────────────────────────────────────────

    suspend fun createUser(
        uid: String,
        email: String,
        name: String,
        quota: Int,
    ): Result<Unit> = runCatching {
        val dto = mapOf(
            "email" to email,
            "name" to name,
            "email_verified" to false,
            "phone_number" to "",
            "phone_verified" to false,
            "assigned_quota" to quota,
            "remaining_quota" to quota,
            "last_quota_reset_date" to DateUtils.todayString(),
            "created_at" to Timestamp.now(),
        )
        db.collection(Paths.USERS).document(uid).set(dto).await()
    }

    suspend fun getUser(uid: String): Result<UserDto?> = runCatching {
        val snap = db.collection(Paths.USERS).document(uid).get().await()
        if (snap.exists()) snap.toObject(UserDto::class.java)?.copy(uid = snap.id) else null
    }

    suspend fun updateQuotaReset(uid: String, remaining: Int, date: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).update(
            mapOf("remaining_quota" to remaining, "last_quota_reset_date" to date)
        ).await()
    }

    suspend fun decrementRemainingQuota(uid: String): Result<Unit> = runCatching {
        val ref = db.collection(Paths.USERS).document(uid)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val current = snap.getLong("remaining_quota")?.toInt() ?: 0
            if (current > 0) tx.update(ref, "remaining_quota", current - 1)
        }.await()
    }

    suspend fun syncEmailVerified(uid: String, verified: Boolean): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("email_verified", verified).await()
    }

    suspend fun getDeviceFreeSmsQuota(): Result<Int> = runCatching {
        val snap = db.document(Paths.DEVICE_DOC).get().await()
        snap.getLong(Paths.FREE_SMS_QUOTA_FIELD)?.toInt() ?: 10
    }

    // ── Phone verification (V1.5) ─────────────────────────────────────────────

    suspend fun savePhoneNumber(uid: String, phoneNumber: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("phone_number", phoneNumber, "phone_verified", false).await()
    }

    suspend fun savePhoneOtp(uid: String, otp: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("phone_otp", otp).await()
    }

    suspend fun getPhoneOtp(uid: String): Result<String?> = runCatching {
        val snap = db.collection(Paths.USERS).document(uid).get().await()
        snap.getString("phone_otp")
    }

    suspend fun markPhoneVerified(uid: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).update(
            mapOf(
                "phone_verified" to true,
                "phone_otp" to FieldValue.delete(),
            )
        ).await()
    }

    // Enqueues an OTP SMS directly to sms_jobs without creating a history entry or touching quota
    suspend fun enqueueOtpSms(phoneNumber: String, message: String, enqueBy: String): Result<Unit> = runCatching {
        val jobDto = mapOf(
            "message" to message,
            "status" to "pending",
            "enque_by" to enqueBy,
        )
        db.collection(Paths.SMS_JOBS).document(phoneNumber).set(jobDto).await()
    }

    // ── SMS Jobs + History ────────────────────────────────────────────────────

    suspend fun enqueueJob(
        uid: String,
        phoneNumber: String,
        message: String,
    ): Result<String> = runCatching {
        val enqueBy = "app:$uid"
        val now = Timestamp.now()
        val jobDto = SmsJobDto.from(phoneNumber, message, enqueBy, now)
        val historyDto = mapOf(
            "phone_number" to phoneNumber,
            "message" to message,
            "status" to "pending",
            "enqueued_at" to now,
            "job_phone_key" to phoneNumber,
            "enque_by" to enqueBy,
        )
        val batch = db.batch()
        val jobRef = db.collection(Paths.SMS_JOBS).document(phoneNumber)
        val historyRef = db.collection(Paths.USERS).document(uid)
            .collection(Paths.HISTORY_SUB).document()
        batch.set(jobRef, jobDto)
        batch.set(historyRef, historyDto)
        batch.commit().await()
        historyRef.id
    }

    fun getHistory(uid: String): Flow<List<HistoryEntryDto>> =
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.HISTORY_SUB)
            .orderBy("enqueued_at", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    doc.toObject(HistoryEntryDto::class.java)?.copy(id = doc.id)
                }
            }

    suspend fun fetchJobStatus(phoneNumber: String): Result<SmsJobDto> = runCatching {
        val snap = db.collection(Paths.SMS_JOBS).document(phoneNumber).get().await()
        snap.toObject(SmsJobDto::class.java) ?: error("Job not found for $phoneNumber")
    }

    suspend fun updateHistoryStatus(
        uid: String,
        historyId: String,
        status: String,
    ): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.HISTORY_SUB).document(historyId)
            .update("status", status).await()
    }
}
