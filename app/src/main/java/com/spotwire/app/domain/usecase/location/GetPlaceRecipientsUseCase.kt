package com.spotwire.app.domain.usecase.location

import com.spotwire.app.domain.model.PlaceContact
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository

/**
 * Everyone an alert for one place goes to: every guardian, the contacts saved on
 * the place itself, and any linked account allowed automatic updates.
 * It is the same list an arrival alert builds, so the "send my location" prompt
 * offers exactly the people who would have been told automatically.
 */
class GetPlaceRecipientsUseCase(
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
) {
    suspend operator fun invoke(uid: String, placeId: String): List<PlaceContact> {
        val user = userRepo.getCurrentUser() ?: return emptyList()
        val place = user.places.find { it.id == placeId } ?: return emptyList()
        val linked = linkRepo.activeLinks(uid)
            .filter { it.permissions.autoLocationUpdates }
            .map { PlaceContact(name = it.otherName, number = it.otherPhone) }
        // Named entries first so a number that appears twice keeps its name.
        val guardians = user.guardianNumbers.map { PlaceContact("Guardian", it) }
        return (place.contacts + linked + guardians)
            .filter { it.number.isNotBlank() }
            .distinctBy { it.number }
    }
}
