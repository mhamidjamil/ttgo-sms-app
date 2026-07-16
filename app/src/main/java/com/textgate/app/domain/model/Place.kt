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

// A saved arrival location. "home" and "office" are seeded defaults; users can
// add any number of custom places (friend's house, gym, …) with their own
// arrival message and their own contact list.
data class Place(
    val id: String,
    val label: String,
    val bssid: String = "",
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
) {
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
