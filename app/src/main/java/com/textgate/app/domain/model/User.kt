package com.textgate.app.domain.model

data class User(
    val uid: String,
    val email: String,
    val name: String,
    val emailVerified: Boolean,
    val phoneNumber: String,
    val phoneVerified: Boolean,
    val assignedQuota: Int,
    val remainingQuota: Int,
    val lastQuotaResetDate: String,
)
