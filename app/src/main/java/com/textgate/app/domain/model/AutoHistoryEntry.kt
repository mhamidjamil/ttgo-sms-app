package com.textgate.app.domain.model

import java.util.Date

data class AutoHistoryEntry(
    val id: String,
    val location: String,       // "home" | "office"
    val sentAt: Date?,
    val status: SmsStatus,
    val jobPhoneKey: String,
    val message: String,
    val routineTriggered: Boolean,
)
