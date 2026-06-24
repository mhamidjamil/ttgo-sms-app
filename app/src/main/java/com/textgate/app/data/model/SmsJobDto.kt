package com.textgate.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import com.textgate.app.domain.model.SmsJob
import com.textgate.app.domain.model.SmsStatus

data class SmsJobDto(
    @get:PropertyName("phone_number") @set:PropertyName("phone_number")
    var phoneNumber: String = "",
    val message: String = "",
    val status: String = "pending",
    @get:PropertyName("enque_by") @set:PropertyName("enque_by")
    var enqueBy: String = "",
    @get:PropertyName("created_at") @set:PropertyName("created_at")
    var createdAt: Timestamp? = null,
    val error: String? = null,
) {
    fun toDomain() = SmsJob(
        phoneNumber = phoneNumber,
        message = message,
        status = SmsStatus.from(status),
        enqueBy = enqueBy,
        createdAt = createdAt,
        error = error,
    )

    companion object {
        fun from(
            phoneNumber: String,
            message: String,
            enqueBy: String,
            createdAt: Timestamp,
        ) = SmsJobDto(
            phoneNumber = phoneNumber,
            message = message,
            status = "pending",
            enqueBy = enqueBy,
            createdAt = createdAt,
        )
    }
}
