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

/**
 * Where a gateway job got to. A write that the server has not acknowledged yet
 * is not a failure: Firestore keeps it in its own queue on the phone, through a
 * restart, and sends it the moment there is a network. Telling the two apart is
 * what stops the app claiming an alert went out while it is still sitting here.
 */
enum class EnqueueResult { SENT_TO_SERVER, QUEUED_ON_DEVICE }
