package com.spotwire.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.IncomingAlert

/**
 * One alert delivered inside the app, written by a linked sender onto the
 * recipient's own account. It carries only what was sent to that person: never
 * the sender's own history, and never a place they were not told about.
 */
data class IncomingAlertDto(
    @DocumentId val id: String = "",
    @get:PropertyName("sender_uid") @set:PropertyName("sender_uid")
    var senderUid: String = "",
    @get:PropertyName("sender_name") @set:PropertyName("sender_name")
    var senderName: String = "",
    @get:PropertyName("sender_phone") @set:PropertyName("sender_phone")
    var senderPhone: String = "",
    val message: String = "",
    @get:PropertyName("place_label") @set:PropertyName("place_label")
    var placeLabel: String = "",
    @get:PropertyName("sent_at") @set:PropertyName("sent_at")
    var sentAt: Timestamp? = null,
    @get:PropertyName("seen_at") @set:PropertyName("seen_at")
    var seenAt: Timestamp? = null,
) {
    fun toDomain() = IncomingAlert(
        id = id,
        senderUid = senderUid,
        senderName = senderName,
        senderPhone = senderPhone,
        message = message,
        placeLabel = placeLabel,
        sentAt = sentAt,
        seen = seenAt != null,
    )
}
