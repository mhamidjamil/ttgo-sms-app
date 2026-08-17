package com.spotwire.app.domain.model

/**
 * One finished stay, as it is kept on the account rather than on the phone.
 *
 * The label travels with the row on purpose. The user document that holds the
 * place list is readable by its owner and nobody else, so a guardian handed only
 * a place id could never turn it into a name.
 */
data class PlaceVisit(
    val id: String,
    val placeId: String,
    val label: String,
    val startedAt: Long,
    val endedAt: Long,
) {
    val millis: Long get() = endedAt - startedAt
}
