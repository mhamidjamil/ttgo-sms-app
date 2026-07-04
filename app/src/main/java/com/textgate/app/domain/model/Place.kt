package com.textgate.app.domain.model

// A person notified when the user arrives at a place (e.g. Wife, Manager).
// Name is optional display/personalization info; number is E.164.
data class PlaceContact(
    val name: String = "",
    val number: String = "",
)

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
) {
    companion object {
        const val HOME_ID = "home"
        const val OFFICE_ID = "office"

        fun defaults() = listOf(
            Place(HOME_ID, "Home"),
            Place(OFFICE_ID, "Office"),
        )

        fun isDefaultId(id: String) = id == HOME_ID || id == OFFICE_ID
    }
}
