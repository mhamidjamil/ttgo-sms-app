package com.textgate.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.textgate.app.domain.model.User

data class UserDto(
    @DocumentId val uid: String = "",
    val email: String = "",
    val name: String = "",
    @get:PropertyName("email_verified") @set:PropertyName("email_verified")
    var emailVerified: Boolean = false,
    @get:PropertyName("phone_number") @set:PropertyName("phone_number")
    var phoneNumber: String = "",
    @get:PropertyName("phone_verified") @set:PropertyName("phone_verified")
    var phoneVerified: Boolean = false,
    @get:PropertyName("assigned_quota") @set:PropertyName("assigned_quota")
    var assignedQuota: Int = 0,
    @get:PropertyName("remaining_quota") @set:PropertyName("remaining_quota")
    var remainingQuota: Int = 0,
    @get:PropertyName("last_quota_reset_date") @set:PropertyName("last_quota_reset_date")
    var lastQuotaResetDate: String = "",
    @get:PropertyName("created_at") @set:PropertyName("created_at")
    var createdAt: Timestamp? = null,
) {
    fun toDomain() = User(
        uid = uid,
        email = email,
        name = name,
        emailVerified = emailVerified,
        phoneNumber = phoneNumber,
        phoneVerified = phoneVerified,
        assignedQuota = assignedQuota,
        remainingQuota = remainingQuota,
        lastQuotaResetDate = lastQuotaResetDate,
    )
}
