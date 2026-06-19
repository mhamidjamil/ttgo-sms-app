package com.spotwire.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.AlertSubscription

data class AlertSubscriptionDto(
    @DocumentId val senderUid: String = "",
    @get:PropertyName("sender_name") @set:PropertyName("sender_name")
    var senderName: String = "",
    @get:PropertyName("sender_phone") @set:PropertyName("sender_phone")
    var senderPhone: String = "",
    // Absent means "never opted out" — the field only appears once the recipient
    // makes a choice, so a missing value has to read as subscribed.
    val subscribed: Boolean = true,
    @get:PropertyName("last_alert_at") @set:PropertyName("last_alert_at")
    var lastAlertAt: Timestamp? = null,
) {
    fun toDomain() = AlertSubscription(
        senderUid = senderUid,
        senderName = senderName,
        senderPhone = senderPhone,
        subscribed = subscribed,
        lastAlertAt = lastAlertAt?.toDate(),
    )
}
