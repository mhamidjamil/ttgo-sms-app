package com.spotwire.app.domain.model

data class User(
    val uid: String,
    val email: String,
    val name: String,
    val emailVerified: Boolean,
    val phoneNumber: String,
    val phoneVerified: Boolean,
    val phoneCountry: String = "",
    val assignedQuota: Int,
    val remainingQuota: Int,
    val lastQuotaResetDate: String,

    // The WhatsApp number this account's own gateway key sends from.
    val waSessionId: String = "",

    // V2: arrival monitoring
    val guardianNumber: String = "",
    // Dynamic place list (home/office seeded; users add more). Legacy fixed
    // home/office fields below are kept only so old documents migrate cleanly.
    val places: List<Place> = emptyList(),
    val arrivalTimesByPlace: Map<String, List<String>> = emptyMap(),
    val lastArrivalDateByPlace: Map<String, String> = emptyMap(),
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
