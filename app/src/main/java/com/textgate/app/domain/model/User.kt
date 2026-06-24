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

    // V2: arrival monitoring
    val guardianNumber: String = "",
    val homeBssid: String = "",
    val homeLabel: String = "",
    val officeBssid: String = "",
    val officeLabel: String = "",
    val wifiStabilityMinutes: Int = 10,
    val arrivalHomeTimes: List<String> = emptyList(),
    val arrivalOfficeTimes: List<String> = emptyList(),
    val lastHomeArrivalDate: String = "",
    val lastOfficeArrivalDate: String = "",
)
