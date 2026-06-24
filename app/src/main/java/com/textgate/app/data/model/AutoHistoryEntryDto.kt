package com.textgate.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.model.SmsStatus

data class AutoHistoryEntryDto(
    @DocumentId val id: String = "",
    val location: String = "",
    @get:PropertyName("sent_at") @set:PropertyName("sent_at")
    var sentAt: Timestamp? = null,
    val status: String = "pending",
    @get:PropertyName("job_phone_key") @set:PropertyName("job_phone_key")
    var jobPhoneKey: String = "",
    val message: String = "",
    @get:PropertyName("routine_triggered") @set:PropertyName("routine_triggered")
    var routineTriggered: Boolean = false,
) {
    fun toDomain() = AutoHistoryEntry(
        id = id,
        location = location,
        sentAt = sentAt?.toDate(),
        status = SmsStatus.from(status),
        jobPhoneKey = jobPhoneKey,
        message = message,
        routineTriggered = routineTriggered,
    )
}
