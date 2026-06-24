package com.textgate.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.textgate.app.domain.model.HistoryEntry
import com.textgate.app.domain.model.SmsStatus

data class HistoryEntryDto(
    @DocumentId val id: String = "",
    @get:PropertyName("phone_number") @set:PropertyName("phone_number")
    var phoneNumber: String = "",
    val message: String = "",
    val status: String = "pending",
    @get:PropertyName("enqueued_at") @set:PropertyName("enqueued_at")
    var enqueuedAt: Timestamp? = null,
    @get:PropertyName("job_phone_key") @set:PropertyName("job_phone_key")
    var jobPhoneKey: String = "",
    @get:PropertyName("enque_by") @set:PropertyName("enque_by")
    var enqueBy: String = "",
) {
    fun toDomain() = HistoryEntry(
        id = id,
        phoneNumber = phoneNumber,
        message = message,
        status = SmsStatus.from(status),
        enqueuedAt = enqueuedAt,
        jobPhoneKey = jobPhoneKey,
        enqueBy = enqueBy,
    )
}
