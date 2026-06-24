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

    // V2: arrival monitoring
    @get:PropertyName("guardian_number") @set:PropertyName("guardian_number")
    var guardianNumber: String = "",
    @get:PropertyName("home_bssid") @set:PropertyName("home_bssid")
    var homeBssid: String = "",
    @get:PropertyName("home_label") @set:PropertyName("home_label")
    var homeLabel: String = "",
    @get:PropertyName("office_bssid") @set:PropertyName("office_bssid")
    var officeBssid: String = "",
    @get:PropertyName("office_label") @set:PropertyName("office_label")
    var officeLabel: String = "",
    @get:PropertyName("wifi_stability_minutes") @set:PropertyName("wifi_stability_minutes")
    var wifiStabilityMinutes: Int = 10,
    @get:PropertyName("arrival_home_times") @set:PropertyName("arrival_home_times")
    var arrivalHomeTimes: List<String> = emptyList(),
    @get:PropertyName("arrival_office_times") @set:PropertyName("arrival_office_times")
    var arrivalOfficeTimes: List<String> = emptyList(),
    @get:PropertyName("last_home_arrival_date") @set:PropertyName("last_home_arrival_date")
    var lastHomeArrivalDate: String = "",
    @get:PropertyName("last_office_arrival_date") @set:PropertyName("last_office_arrival_date")
    var lastOfficeArrivalDate: String = "",
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
        guardianNumber = guardianNumber,
        homeBssid = homeBssid,
        homeLabel = homeLabel,
        officeBssid = officeBssid,
        officeLabel = officeLabel,
        wifiStabilityMinutes = wifiStabilityMinutes,
        arrivalHomeTimes = arrivalHomeTimes,
        arrivalOfficeTimes = arrivalOfficeTimes,
        lastHomeArrivalDate = lastHomeArrivalDate,
        lastOfficeArrivalDate = lastOfficeArrivalDate,
    )
}
