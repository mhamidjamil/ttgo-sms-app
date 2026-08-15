package com.spotwire.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.HistoryEntry
import com.spotwire.app.domain.model.SmsStatus

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
    @get:PropertyName("job_id") @set:PropertyName("job_id")
    var jobId: String = "",
    @get:PropertyName("enque_by") @set:PropertyName("enque_by")
    var enqueBy: String = "",
    // "sms" or "whatsapp". Absent on every row written before manual sending
    // could take the WhatsApp route, and those were all text messages.
    val channel: String = "sms",
    // The gateway's own id for a WhatsApp message, which is how its status is
    // looked up afterwards instead of assumed.
    @get:PropertyName("wa_message_id") @set:PropertyName("wa_message_id")
    var waMessageId: String = "",
    // Why it failed, in the words of whatever refused it.
    val error: String = "",
) {
    fun toDomain() = HistoryEntry(
        id = id,
        phoneNumber = phoneNumber,
        message = message,
        status = SmsStatus.from(status),
        enqueuedAt = enqueuedAt,
        jobPhoneKey = jobPhoneKey,
        jobId = jobId,
        enqueBy = enqueBy,
        channel = channel.ifBlank { "sms" },
        waMessageId = waMessageId,
        error = error,
    )
}
