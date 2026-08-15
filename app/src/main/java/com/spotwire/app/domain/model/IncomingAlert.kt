package com.spotwire.app.domain.model

import com.google.firebase.Timestamp

data class IncomingAlert(
    val id: String,
    val senderUid: String,
    val senderName: String,
    val senderPhone: String,
    val message: String,
    val placeLabel: String,
    val sentAt: Timestamp?,
    val seen: Boolean,
)
