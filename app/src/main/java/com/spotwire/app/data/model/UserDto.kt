package com.spotwire.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.AlertRoutes
import com.spotwire.app.domain.model.Closeness
import com.spotwire.app.domain.model.Place
import com.spotwire.app.domain.model.PlaceContact
import com.spotwire.app.domain.model.User

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
    // Two-letter country the number was entered for, so a saved number can be
    // shown and re-edited the way its owner typed it.
    @get:PropertyName("phone_country") @set:PropertyName("phone_country")
    var phoneCountry: String = "",
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
    // How arrival alerts go out: "both" (default), "in_app" or "message".
    @get:PropertyName("alert_routes") @set:PropertyName("alert_routes")
    var alertRoutes: String = "both",
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

    // The gateway key this user created on the portal, and the WhatsApp number
    // it is bound to. Every message this account sends leaves from that number.
    @get:PropertyName("wa_session_id") @set:PropertyName("wa_session_id")
    var waSessionId: String = "",
    @get:PropertyName("wa_key_id") @set:PropertyName("wa_key_id")
    var waKeyId: String = "",
    @get:PropertyName("wa_key_secret") @set:PropertyName("wa_key_secret")
    var waKeySecret: String = "",

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
            // contacts: [{name, number}] — migrate legacy bare-number
            // "recipients" arrays into contacts with blank names.
            val contactMaps = (raw["contacts"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            val contacts = contactMaps?.mapNotNull { c ->
                val number = c["number"] as? String ?: return@mapNotNull null
                if (number.isBlank()) null
                else PlaceContact(name = c["name"] as? String ?: "", number = number)
            } ?: (raw["recipients"] as? List<*>)?.filterIsInstance<String>()
                ?.filter { it.isNotBlank() }?.map { PlaceContact(name = "", number = it) }
                .orEmpty()
            Place(
                id = id,
                label = raw["label"] as? String ?: "",
                bssid = raw["bssid"] as? String ?: "",
                message = raw["message"] as? String ?: "",
                waMessage = raw["wa_message"] as? String ?: "",
                contacts = contacts,
                bssids = (raw["bssids"] as? List<*>)?.filterIsInstance<String>()
                    ?.filter { it.isNotBlank() }.orEmpty(),
                minRssi = (raw["min_rssi"] as? Number)?.toInt() ?: Closeness.NEARBY.dbm,
                alertsEnabled = raw["alerts_enabled"] as? Boolean ?: true,
                sensitivity = raw["sensitivity"] as? String ?: "",
                dwellMinutesOverride = (raw["dwell_minutes"] as? Number)?.toInt() ?: 0,
                quietFrom = raw["quiet_from"] as? String ?: "",
                quietTo = raw["quiet_to"] as? String ?: "",
                latitude = (raw["latitude"] as? Number)?.toDouble() ?: 0.0,
                longitude = (raw["longitude"] as? Number)?.toDouble() ?: 0.0,
                radiusMeters = (raw["radius_m"] as? Number)?.toInt() ?: 0,
            )
        }.ifEmpty {
            // Only a genuinely legacy document, one that predates the places
            // array and shows it by carrying the old fixed fields, gets
            // home/office synthesized. An array the user emptied by deleting
            // every place must stay empty rather than resurrect the pair.
            if (listOf(homeLabel, homeBssid, officeLabel, officeBssid).all { it.isBlank() }) emptyList()
            else listOf(
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
            phoneCountry = phoneCountry,
            assignedQuota = assignedQuota,
            remainingQuota = remainingQuota,
            lastQuotaResetDate = lastQuotaResetDate,
            waSessionId = waSessionId,
            guardianNumber = guardianNumber,
            alertRoutes = alertRoutes.ifBlank { AlertRoutes.BOTH },
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
