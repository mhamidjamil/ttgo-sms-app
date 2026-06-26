package com.textgate.app.domain.usecase.location

import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.PlaceContact
import com.textgate.app.domain.model.SettingsChange
import com.textgate.app.domain.repository.UserRepository

class SavePlacesUseCase(private val userRepo: UserRepository) {

    suspend operator fun invoke(
        uid: String,
        guardianNumber: String,
        places: List<Place>,
    ): Result<Unit> {
        val before = userRepo.getCurrentUser()
        val result = userRepo.savePlacesSettings(uid, guardianNumber, places)
        if (result.isSuccess && before != null) {
            val changes = buildList {
                if (before.guardianNumber != guardianNumber) {
                    add(SettingsChange(
                        field = "Guardian number",
                        oldValue = before.guardianNumber,
                        newValue = guardianNumber,
                    ))
                }
                addAll(placeChanges(before.places, places))
            }
            userRepo.logSettingsChanges(uid, changes)
        }
        return result
    }

    // Places-only save for the place editor and the WiFi picker: contacts and a
    // picked network reach Firestore as soon as the dialog is confirmed. They
    // used to sit in memory until the separate "Save Settings" button at the
    // bottom of the screen was pressed, which is how added contacts vanished.
    suspend fun savePlacesOnly(uid: String, places: List<Place>): Result<Unit> {
        val before = userRepo.getCurrentUser()
        val result = userRepo.savePlaces(uid, places)
        if (result.isSuccess && before != null) {
            userRepo.logSettingsChanges(uid, placeChanges(before.places, places))
        }
        return result
    }

    private fun placeChanges(old: List<Place>, new: List<Place>): List<SettingsChange> = buildList {
        val oldById = old.associateBy { it.id }
        val newIds = new.map { it.id }.toSet()
        new.forEach { place ->
            val name = place.label.ifBlank { place.id }
            val previous = oldById[place.id]
            if (previous == null) {
                add(SettingsChange(field = "Place added", oldValue = "", newValue = name))
                return@forEach
            }
            if (previous.label != place.label) {
                add(SettingsChange(field = "Place name", oldValue = previous.label, newValue = place.label))
            }
            if (previous.bssid != place.bssid) {
                add(SettingsChange(field = "$name WiFi", oldValue = previous.bssid, newValue = place.bssid))
            }
            if (previous.message != place.message) {
                add(SettingsChange(field = "$name message", oldValue = previous.message, newValue = place.message))
            }
            if (previous.waMessage != place.waMessage) {
                add(SettingsChange(
                    field = "$name WhatsApp message",
                    oldValue = previous.waMessage,
                    newValue = place.waMessage,
                ))
            }
            if (previous.contacts != place.contacts) {
                add(SettingsChange(
                    field = "$name contacts",
                    oldValue = describe(previous.contacts),
                    newValue = describe(place.contacts),
                ))
            }
        }
        old.filterNot { it.id in newIds }.forEach { removed ->
            add(SettingsChange(
                field = "Place removed",
                oldValue = removed.label.ifBlank { removed.id },
                newValue = "",
            ))
        }
    }

    private fun describe(contacts: List<PlaceContact>): String =
        if (contacts.isEmpty()) "none (default guardian)"
        else contacts.joinToString(", ") { it.name.ifBlank { it.number } }
}
