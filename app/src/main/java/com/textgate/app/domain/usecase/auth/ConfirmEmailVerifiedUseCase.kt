package com.textgate.app.domain.usecase.auth

import com.textgate.app.domain.repository.UserRepository

/**
 * Checks whether the emailed link has been opened yet. Firebase only updates
 * the cached user on a reload, so this asks the server and mirrors the answer
 * into the Firestore document the rest of the app reads.
 */
class ConfirmEmailVerifiedUseCase(private val userRepo: UserRepository) {
    suspend operator fun invoke(): Result<Unit> = runCatching {
        val verified = userRepo.refreshEmailVerified().getOrThrow()
        if (!verified) {
            error("Not verified yet. Open the link in the email we sent, then tap this again.")
        }
    }
}
