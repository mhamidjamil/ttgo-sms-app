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
    // Everyone who is told about every place, on top of whatever contacts a
    // place has of its own. A household usually has two of these; the single
    // number this replaced could not describe two parents.
    val guardianNumbers: List<String> = emptyList(),
    // Which routes an arrival alert may take. Somebody whose people all have the
    // app does not want a text message as well, and somebody whose people do not
    // have it must not have alerts quietly parked in an app nobody installed.
    val alertRoutes: String = AlertRoutes.BOTH,
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
