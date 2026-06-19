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
    suspend operator fun invoke(rawPhone: String, permissions: LinkPermissions): Result<String> {
        val normalized = normalizer.normalize(rawPhone)
            ?: return Result.failure(IllegalArgumentException("Enter a valid Pakistani number"))
        val me = userRepo.getCurrentUser()
            ?: return Result.failure(IllegalStateException("Could not load your profile"))
        if (!me.phoneVerified) {
            return Result.failure(IllegalStateException("Verify your own number before linking accounts"))
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
