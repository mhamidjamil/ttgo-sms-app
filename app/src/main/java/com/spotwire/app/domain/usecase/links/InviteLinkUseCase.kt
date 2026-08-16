package com.spotwire.app.domain.usecase.links

import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.model.LinkPermissions
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository

// Invites another registered user to link accounts. Both sides have to approve
// before the link does anything, so this only creates the pending pair.
class InviteLinkUseCase(
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
    private val normalizer: PhoneNormalizer,
) {
    suspend operator fun invoke(
        rawPhone: String,
        permissions: LinkPermissions,
        countryIso: String,
    ): Result<String> {
        val normalized = normalizer.normalize(rawPhone, countryIso)
            ?: return Result.failure(
                IllegalArgumentException("Enter a valid mobile number for the country selected")
            )
        val me = userRepo.getCurrentUser()
            ?: return Result.failure(IllegalStateException("Could not load your profile"))
        // Confirmed by either route. A number outside Pakistan cannot receive a
        // code from the one device that sends them, and refusing those accounts
        // here would leave the in-app alerts they depend on with nobody to link.
        if (!me.phoneVerified && !me.emailVerified) {
            return Result.failure(
                IllegalStateException("Confirm your account before linking with anyone")
            )
        }
        if (normalized == me.phoneNumber) {
            return Result.failure(IllegalArgumentException("That is your own number"))
        }
        val found = linkRepo.lookupByPhone(normalized).getOrElse { return Result.failure(it) }
            ?: return Result.failure(
                IllegalStateException("No Spotwire account uses $normalized yet. Ask them to sign up and verify it first.")
            )
        val (otherUid, otherName) = found
        return linkRepo.invite(
            myUid = me.uid,
            myName = me.name,
            myPhone = me.phoneNumber,
            otherUid = otherUid,
            otherName = otherName,
            otherPhone = normalized,
            permissions = permissions,
        ).map { otherName.ifBlank { normalized } }
    }
}
