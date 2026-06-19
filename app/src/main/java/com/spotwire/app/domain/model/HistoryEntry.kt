package com.spotwire.app.domain.model

import com.google.firebase.Timestamp

data class HistoryEntry(
    val id: String,
    val phoneNumber: String,
    val message: String,
    val status: SmsStatus,
    val enqueuedAt: Timestamp?,
    val jobPhoneKey: String,
    val enqueBy: String,
    // Gateway job document id. Blank on entries written before jobs stopped
    // being keyed by phone number; those fall back to jobPhoneKey.
    val jobId: String = "",
)
