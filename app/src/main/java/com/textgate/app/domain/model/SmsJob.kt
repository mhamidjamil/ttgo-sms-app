package com.textgate.app.domain.model

import com.google.firebase.Timestamp

data class SmsJob(
    val phoneNumber: String,
    val message: String,
    val status: SmsStatus,
    val enqueBy: String,
    val createdAt: Timestamp?,
    val error: String? = null,
)
