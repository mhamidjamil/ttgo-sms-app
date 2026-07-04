package com.textgate.app.domain.model

// A saved arrival location. "home" and "office" are seeded defaults; users can
// add any number of custom places (friend's house, gym, …) with their own
// arrival message.
data class Place(
    val id: String,
    val label: String,
    val bssid: String = "",
    // Custom arrival message sent to the guardian; blank → default
    // "<name> arrived at <label>".
    val message: String = "",
    // E.164 numbers notified for THIS place, chosen from the user's saved
    // guardian numbers. Empty → the default guardian number.
    val recipients: List<String> = emptyList(),
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
