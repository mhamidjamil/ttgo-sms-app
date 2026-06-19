package com.spotwire.app.domain.model

import java.util.Date

// An on-demand "where are you right now?" ask, written into the TARGET user's
// own subcollection. Their device answers it with a place label only — never a
// BSSID and never coordinates.
data class LocationRequest(
    val id: String,
    val requesterUid: String,
    val requesterName: String,
    val status: String,          // "pending" | "answered" | "denied"
    val answer: String = "",
    val createdAt: Date? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val ANSWERED = "answered"
        const val DENIED = "denied"
    }
}
