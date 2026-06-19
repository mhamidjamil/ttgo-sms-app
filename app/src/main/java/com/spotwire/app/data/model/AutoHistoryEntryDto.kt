package com.spotwire.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.AutoHistoryEntry
import com.spotwire.app.domain.model.SmsStatus

data class AutoHistoryEntryDto(
    @DocumentId val id: String = "",
    val location: String = "",
    @get:PropertyName("location_label") @set:PropertyName("location_label")
    var locationLabel: String = "",
    val channel: String = "sms",
    @get:PropertyName("enque_by") @set:PropertyName("enque_by")
    var enqueBy: String = "",
    @get:PropertyName("sent_at") @set:PropertyName("sent_at")
    var sentAt: Timestamp? = null,
    val status: String = "pending",
    @get:PropertyName("job_phone_key") @set:PropertyName("job_phone_key")
    var jobPhoneKey: String = "",
    @get:PropertyName("job_id") @set:PropertyName("job_id")
    var jobId: String = "",
    @get:PropertyName("recipient_name") @set:PropertyName("recipient_name")
    var recipientName: String = "",
    val message: String = "",
    @get:PropertyName("routine_triggered") @set:PropertyName("routine_triggered")
    var routineTriggered: Boolean = false,
    // Why the gateway refused this recipient. It has always been written on a
    // failed row and never read back, so the Auto page could only say "Failed".
    val error: String = "",
    // Not a stored field: it comes from the snapshot metadata and says the row
    // exists only in this phone's cache so far.
    @get:Exclude val pendingWrite: Boolean = false,
) {
    fun toDomain() = AutoHistoryEntry(
        id = id,
        location = location,
        locationLabel = locationLabel,
        channel = channel.ifBlank { "sms" },
        enqueBy = enqueBy,
        sentAt = sentAt?.toDate(),
        status = SmsStatus.from(status),
        jobPhoneKey = jobPhoneKey,
        jobId = jobId,
        recipientName = recipientName,
        message = message,
        routineTriggered = routineTriggered,
        error = error,
        pendingWrite = pendingWrite,
    )
}
