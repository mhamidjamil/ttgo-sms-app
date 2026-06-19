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
    @get:PropertyName("created_at") @set:PropertyName("created_at")
    var createdAt: Timestamp? = null,
) {
    fun toDomain() = LocationRequest(
        id = id,
        requesterUid = requesterUid,
        requesterName = requesterName,
        status = status,
        answer = answer,
        createdAt = createdAt?.toDate(),
    )
}
