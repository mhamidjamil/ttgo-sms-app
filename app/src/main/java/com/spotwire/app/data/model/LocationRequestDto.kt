package com.spotwire.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.LocationRequest

data class LocationRequestDto(
    @DocumentId val id: String = "",
    @get:PropertyName("requester_uid") @set:PropertyName("requester_uid")
    var requesterUid: String = "",
    @get:PropertyName("requester_name") @set:PropertyName("requester_name")
    var requesterName: String = "",
    val status: String = "pending",
    val answer: String = "",
    // Absent on every request written before precise mode existed, so the
    // default is what those have always meant: a place name only.
    val mode: String = LocationRequest.PLACE,
    // The one field the ASKER may write, so a request they opened can be called
    // off from their side instead of only from the phone being asked.
    @get:PropertyName("stop_requested") @set:PropertyName("stop_requested")
    var stopRequested: Boolean = false,
    @get:PropertyName("created_at") @set:PropertyName("created_at")
    var createdAt: Timestamp? = null,
) {
    fun toDomain() = LocationRequest(
        id = id,
        requesterUid = requesterUid,
        requesterName = requesterName,
        status = status,
        answer = answer,
        mode = mode.ifBlank { LocationRequest.PLACE },
        stopRequested = stopRequested,
        createdAt = createdAt?.toDate(),
    )
}
