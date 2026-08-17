package com.spotwire.app.domain.model

import java.util.Date

// An on-demand "where are you right now?" ask, written into the TARGET user's
// own subcollection.
//
// A PLACE ask is answered with a saved place's name and nothing else, which is
// what this has always done. A PRECISE ask is answered with the coordinates,
// how accurate the reading was, and the wireless networks the phone can hear,
// and it stays open until the asker calls it off, so the fix sharpens as the
// satellites come in. It is refused unless the person being asked has switched
// that on for this one account.
data class LocationRequest(
    val id: String,
    val requesterUid: String,
    val requesterName: String,
    val status: String,          // "pending" | "answered" | "denied"
    val answer: String = "",
    val mode: String = PLACE,    // "place" | "precise"
    val stopRequested: Boolean = false,
    val createdAt: Date? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val ANSWERED = "answered"
        const val DENIED = "denied"

        const val PLACE = "place"
        const val PRECISE = "precise"
    }
}

/**
 * One reading sent back for a precise ask. A request left open collects a run of
 * these, so the asker watches the position settle instead of being handed one
 * fix that may have landed while the phone still had nothing but cell towers.
 */
data class LocationAnswer(
    val id: String = "",
    val at: Long = 0L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val placeLabel: String = "",
    // "name|hardware id|signal" per network, loudest first. The names are the
    // part that actually says where somebody is: a shop or a house name beats a
    // pair of numbers for working out where a child has stopped.
    val networks: List<String> = emptyList(),
)
