package com.textgate.app.domain.model

// A person notified when the user arrives at a place (e.g. Wife, Manager).
// Name is optional display/personalization info; number is E.164.
data class PlaceContact(
    val name: String = "",
    val number: String = "",
)

// How settled the phone has to be before this place is allowed to alert. The
// numbers are the minutes it must stay put; shorter catches brief visits but is
// likelier to announce a visit that did not really happen.
enum class Sensitivity(val id: String, val label: String, val dwellMinutes: Int) {
    QUICK("quick", "Quick", 3),
    BALANCED("balanced", "Balanced", 8),
    CAREFUL("careful", "Careful", 20);

    companion object {
        fun from(id: String): Sensitivity? = entries.firstOrNull { it.id == id }
    }
}

// How close the phone has to be for a place to count as reached.
enum class Closeness(val id: String, val label: String, val dbm: Int) {
    ANY("any", "Any signal", -100),
    NEARBY("nearby", "Nearby", -80),
    INSIDE("inside", "Inside only", -65);

    companion object {
        // The nearest chip at or below the stored number, so an exact value typed
        // into the advanced drawer still shows the closest matching chip.
        fun forDbm(dbm: Int): Closeness = entries.lastOrNull { dbm <= it.dbm } ?: INSIDE
    }
}

// A saved arrival location. "home" and "office" are seeded defaults; users can
// add any number of custom places (friend's house, gym, …) with their own
// arrival message and their own contact list.
data class Place(
    val id: String,
    val label: String,
    // The strongest saved network. Kept alongside the full set so a copy of the
    // app that predates multiple networks still detects this place.
    val bssid: String = "",
    // Every access point saved for this place: a second radio band, a mesh node,
    // a replacement router. One dropping out of a scan must not blind the place.
    val bssids: List<String> = emptyList(),
    // How loud the strongest match has to be. Walking past outside reads much
    // weaker than standing inside, so this is what separates the two.
    val minRssi: Int = Closeness.NEARBY.dbm,
    // Custom arrival message; blank → default "<name> arrived at <label>".
    val message: String = "",
    // Optional separate message for WhatsApp deliveries; blank → same as SMS.
    val waMessage: String = "",
    // People notified for THIS place. Empty → the default guardian number.
    val contacts: List<PlaceContact> = emptyList(),
    // Off keeps the place saved and still answers "where am I", but never alerts.
    val alertsEnabled: Boolean = true,
    // Blank until the user picks one, so places saved before this setting
    // existed keep the app-wide wait they were already using.
    val sensitivity: String = "",
    // Exact wait in minutes from the advanced drawer; 0 → follow the preset.
    val dwellMinutesOverride: Int = 0,
    // "HH:mm" pair; blank → no quiet window. Sending is suppressed inside it.
    val quietFrom: String = "",
    val quietTo: String = "",
    // Where the place is on the map, captured from a location fix or typed in.
    // Zero means never set, which is what every place saved before this existed
    // looks like.
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    // How far from that point still counts as being here.
    val radiusMeters: Int = 0,
) {
    // No radius means the place is WiFi-only, exactly as it was before coordinates existed.
    val hasGeofence: Boolean
        get() = radiusMeters > 0 && (latitude != 0.0 || longitude != 0.0)

    // Every network saved for this place, however it was saved.
    val savedBssids: List<String>
        get() = (bssids + bssid).filter { it.isNotBlank() }.map { it.lowercase() }.distinct()

    // One access point dropping out of a scan is normal, so a place with a mesh
    // must not need every node. Two is enough to rule out a recycled identifier,
    // and asking for more makes detection worse rather than better.
    val requiredMatches: Int
        get() = if (savedBssids.size >= 3) 2 else 1

    // Present when enough of this place's networks are audible and the loudest
    // of them clears the closeness floor.
    fun isPresentIn(visible: Map<String, Int>): Boolean {
        val heard = savedBssids.mapNotNull { visible[it] }
        return heard.size >= requiredMatches && (heard.maxOrNull() ?: return false) >= minRssi
    }

    // The wait actually applied: an exact override wins, then the chosen preset,
    // then whatever the app was using before any of this was configurable.
    fun effectiveDwellMinutes(fallbackMinutes: Int): Int = when {
        dwellMinutesOverride > 0 -> dwellMinutesOverride
        else -> Sensitivity.from(sensitivity)?.dwellMinutes ?: fallbackMinutes
    }

    // Minutes past midnight, so a window that runs past midnight still reads as
    // one range rather than two.
    fun isQuietAt(minutesOfDay: Int): Boolean {
        val from = parseHhMm(quietFrom) ?: return false
        val to = parseHhMm(quietTo) ?: return false
        if (from == to) return false
        return if (from < to) minutesOfDay in from until to
        else minutesOfDay >= from || minutesOfDay < to
    }

    companion object {
        const val HOME_ID = "home"
        const val OFFICE_ID = "office"

        // A house sits well inside 50 m, and anything under that is smaller than
        // the fix accuracy a phone usually manages. The cap is 500 m because the
        // largest place anyone here has is a couple of kanal, which fits in 200.
        const val DEFAULT_RADIUS_METERS = 50
        const val MIN_RADIUS_METERS = 50
        const val MAX_RADIUS_METERS = 500

        fun parseHhMm(value: String): Int? {
            val parts = value.split(":").takeIf { it.size == 2 } ?: return null
            val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return h * 60 + m
        }

        fun defaults() = listOf(
            Place(HOME_ID, "Home"),
            Place(OFFICE_ID, "Office"),
        )

        fun isDefaultId(id: String) = id == HOME_ID || id == OFFICE_ID
    }
}
