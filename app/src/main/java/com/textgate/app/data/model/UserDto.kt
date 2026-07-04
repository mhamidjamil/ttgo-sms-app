package com.textgate.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.textgate.app.domain.model.Place
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
    @get:PropertyName("guardian_numbers") @set:PropertyName("guardian_numbers")
    var guardianNumbers: List<String> = emptyList(),
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

    // Dynamic places: [{id,label,bssid,message}] + per-place arrival state maps.
    @get:PropertyName("places") @set:PropertyName("places")
    var places: List<Map<String, Any>> = emptyList(),
    @get:PropertyName("arrival_times") @set:PropertyName("arrival_times")
    var arrivalTimes: Map<String, List<String>> = emptyMap(),
    @get:PropertyName("last_arrival_dates") @set:PropertyName("last_arrival_dates")
    var lastArrivalDates: Map<String, String> = emptyMap(),
) {
    fun toDomain(): User {
        // Migrate old documents on the fly: when the places array is absent,
        // synthesize home/office from the legacy fixed fields. The next save
        // writes the places array and the legacy fields stop mattering.
        val placeList = places.mapNotNull { raw ->
            val id = raw["id"] as? String ?: return@mapNotNull null
            Place(
                id = id,
                label = raw["label"] as? String ?: "",
                bssid = raw["bssid"] as? String ?: "",
                message = raw["message"] as? String ?: "",
                recipients = (raw["recipients"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            )
        }.ifEmpty {
            listOf(
                Place(Place.HOME_ID, homeLabel.ifBlank { "Home" }, homeBssid),
                Place(Place.OFFICE_ID, officeLabel.ifBlank { "Office" }, officeBssid),
            )
        }
        val timesByPlace = buildMap {
            putAll(arrivalTimes)
            if (!containsKey(Place.HOME_ID) && arrivalHomeTimes.isNotEmpty()) put(Place.HOME_ID, arrivalHomeTimes)
            if (!containsKey(Place.OFFICE_ID) && arrivalOfficeTimes.isNotEmpty()) put(Place.OFFICE_ID, arrivalOfficeTimes)
        }
        val lastDatesByPlace = buildMap {
            putAll(lastArrivalDates)
            if (!containsKey(Place.HOME_ID) && lastHomeArrivalDate.isNotBlank()) put(Place.HOME_ID, lastHomeArrivalDate)
            if (!containsKey(Place.OFFICE_ID) && lastOfficeArrivalDate.isNotBlank()) put(Place.OFFICE_ID, lastOfficeArrivalDate)
        }
        return User(
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
            guardianNumbers = guardianNumbers,
            places = placeList,
            arrivalTimesByPlace = timesByPlace,
            lastArrivalDateByPlace = lastDatesByPlace,
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
}
