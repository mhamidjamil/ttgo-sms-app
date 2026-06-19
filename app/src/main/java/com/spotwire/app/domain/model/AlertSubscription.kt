package com.spotwire.app.domain.model

import java.util.Date

// One sender-to-recipient relationship for automated location alerts, owned by
// the RECIPIENT. Stored under their phone number rather than their uid so the
// record can be created long before that person installs the app, and found
// again as soon as they verify the same number.
data class AlertSubscription(
    val senderUid: String,
    val senderName: String,
    val senderPhone: String,
    val subscribed: Boolean = true,
    val lastAlertAt: Date? = null,
)
