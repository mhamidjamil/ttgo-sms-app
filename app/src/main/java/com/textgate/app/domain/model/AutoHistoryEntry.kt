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
    // Gateway job document id. Blank on entries written before jobs stopped
    // being keyed by phone number; those fall back to jobPhoneKey.
    val jobId: String = "",
    // Contact name at the time the alert went out, blank for the default
    // guardian and for entries written before names were recorded.
    val recipientName: String = "",
    val message: String,
    val routineTriggered: Boolean,
    // Why the gateway refused it, on failed rows only.
    val error: String = "",
    // True while the row is still only in this phone's cache, so the alert has
    // not reached the gateway yet however "pending" it looks.
    val pendingWrite: Boolean = false,
)
