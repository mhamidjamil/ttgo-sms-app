package com.spotwire.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.PlaceVisit

data class PlaceVisitDto(
    @DocumentId val id: String = "",
    @get:PropertyName("place_id") @set:PropertyName("place_id")
    var placeId: String = "",
    @get:PropertyName("place_label") @set:PropertyName("place_label")
    var placeLabel: String = "",
    // Plain epoch milliseconds rather than a Firestore timestamp, so the row
    // sorts and filters with the same numbers the phone's own copy uses.
    @get:PropertyName("started_at") @set:PropertyName("started_at")
    var startedAt: Long = 0L,
    @get:PropertyName("ended_at") @set:PropertyName("ended_at")
    var endedAt: Long = 0L,
) {
    fun toDomain() = PlaceVisit(
        id = id,
        placeId = placeId,
        label = placeLabel,
        startedAt = startedAt,
        endedAt = endedAt,
    )
}
