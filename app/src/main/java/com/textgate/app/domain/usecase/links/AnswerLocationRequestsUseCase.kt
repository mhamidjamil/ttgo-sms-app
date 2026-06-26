package com.textgate.app.domain.usecase.links

import com.textgate.app.domain.model.LocationRequest
import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.UserRepository

/**
 * Answers one incoming "where are you?" request.
 *
 * The reply is the place LABEL and nothing else: no BSSID, no coordinates. If the
 * current network matches no saved place the answer stays vague on purpose
 * ("not at a saved place"), and a requester without the permission is denied
 * rather than silently ignored, so their screen stops waiting.
 */
class AnswerLocationRequestsUseCase(
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
) {
    suspend operator fun invoke(
        uid: String,
        request: LocationRequest,
        currentBssid: String?,
    ): Result<Unit> {
        val link = linkRepo.activeLinks(uid).firstOrNull { it.otherUid == request.requesterUid }
        if (link == null || !link.permissions.requestLocation) {
            return linkRepo.answerRequest(uid, request.id, LocationRequest.DENIED, "")
        }
        val user = userRepo.getCurrentUser()
            ?: return linkRepo.answerRequest(uid, request.id, LocationRequest.DENIED, "")
        val place = currentBssid?.let { bssid ->
            user.places.firstOrNull { it.bssid.isNotBlank() && it.bssid.equals(bssid, ignoreCase = true) }
        }
        val answer = if (place == null) {
            "${user.name} is not at a saved place right now."
        } else {
            "${user.name} is at ${place.label.ifBlank { place.id }}."
        }
        return linkRepo.answerRequest(uid, request.id, LocationRequest.ANSWERED, answer)
    }
}
