package com.textgate.app.domain.model

import com.google.firebase.Timestamp

data class HistoryEntry(
    val id: String,
    val phoneNumber: String,
    val message: String,
    val status: SmsStatus,
    val enqueuedAt: Timestamp?,
    val jobPhoneKey: String,
    val enqueBy: String,
)
