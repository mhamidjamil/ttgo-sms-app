package com.spotwire.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.spotwire.app.domain.model.LocationAnswer

data class LocationAnswerDto(
    @DocumentId val id: String = "",
    var at: Long = 0L,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    @get:PropertyName("accuracy_m") @set:PropertyName("accuracy_m")
    var accuracyMeters: Double = 0.0,
    @get:PropertyName("place_label") @set:PropertyName("place_label")
    var placeLabel: String = "",
    // Flat strings rather than nested maps: a list of maps costs an index entry
    // per key and nothing here is ever queried by network.
    var networks: List<String> = emptyList(),
) {
    fun toDomain() = LocationAnswer(
        id = id,
        at = at,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters.toFloat(),
        placeLabel = placeLabel,
        networks = networks,
    )
}
