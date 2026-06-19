package com.spotwire.app.domain.model

/**
 * Where the app believes it is, per place.
 *
 * The fourth state is the important one. The old code had only "the network is
 * audible" and "it is not", and resolved every ambiguity to "not", so switching
 * the radios back on in the morning looked exactly like walking in the door.
 * Not being able to observe is its own answer, and nothing may be inferred while
 * it holds.
 */
enum class PresenceState {
    // Observing normally, and this place is not among what can be heard.
    AWAY,

    // This place is audible and the visit clock is running. Nothing sent yet.
    APPROACHING,

    // The visit is confirmed: either an alert went out, or the visit was adopted
    // silently after a blind spell. No further alert until an observed departure.
    HERE,

    // Cannot observe at all: radio off, location off, permission gone, service
    // was dead, or the newest scan is too old to trust.
    BLIND,
}

data class PlacePresence(
    val state: PresenceState = PresenceState.AWAY,
    // When this visit's clock started, and how much of it the phone spent still.
    val visitStartedAt: Long = 0L,
    val stationaryMillis: Long = 0L,
    val lastAlertAt: Long = 0L,
    // Remembered across a blind spell so the app knows whether it was already
    // here when it lost the ability to look.
    val stateBeforeBlind: PresenceState = PresenceState.AWAY,
) {
    fun goBlind(): PlacePresence =
        if (state == PresenceState.BLIND) this
        else copy(state = PresenceState.BLIND, stateBeforeBlind = state)

    // "state|visitStartedAt|stationaryMillis|lastAlertAt|stateBeforeBlind" — a
    // handful of scalars, so a text encoding beats pulling in a serializer.
    fun encode(): String =
        "${state.name}|$visitStartedAt|$stationaryMillis|$lastAlertAt|${stateBeforeBlind.name}"

    companion object {
        fun decode(raw: String?): PlacePresence {
            val parts = raw?.split("|")?.takeIf { it.size == 5 } ?: return PlacePresence()
            return PlacePresence(
                state = runCatching { PresenceState.valueOf(parts[0]) }.getOrDefault(PresenceState.AWAY),
                visitStartedAt = parts[1].toLongOrNull() ?: 0L,
                stationaryMillis = parts[2].toLongOrNull() ?: 0L,
                lastAlertAt = parts[3].toLongOrNull() ?: 0L,
                stateBeforeBlind = runCatching { PresenceState.valueOf(parts[4]) }
                    .getOrDefault(PresenceState.AWAY),
            )
        }
    }
}
