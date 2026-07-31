package com.textgate.app.domain.model

import java.util.Date

data class AutoHistoryEntry(
    val id: String,
    val location: String,       // place id ("home", "office", "place_*")
    val locationLabel: String = "",   // human label at enqueue time
    val channel: String = "sms",      // "sms" | "whatsapp"
    val enqueBy: String = "",
    val sentAt: Date?,
    val status: SmsStatus,
    val jobPhoneKey: String,
    // Contact name at the time the alert went out, blank for the default
    // guardian and for entries written before names were recorded.
    val recipientName: String = "",
    val message: String,
    val routineTriggered: Boolean,
)
