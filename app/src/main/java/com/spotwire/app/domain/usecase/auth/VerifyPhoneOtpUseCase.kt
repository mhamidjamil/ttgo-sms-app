package com.spotwire.app.domain.usecase.auth

import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository

class VerifyPhoneOtpUseCase(
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
) {

    companion object {
        // Codes are valid for 1 hour after being sent.
        const val OTP_VALIDITY_MS = 60L * 60L * 1000L
    }

    /**
     * The code checked out somewhere else and all that is left is to record it.
     * That is the WhatsApp route, where the code never touches this app or its
     * database: the gateway generates it, keeps only a hash of it, and answers
     * yes or no. Nothing here can be replayed against a code it never saw.
     */
    suspend fun confirmVerified(uid: String): Result<Unit> = runCatching {
        userRepo.markPhoneVerified(uid).getOrThrow()
        // Claim the number in the public lookup table so link invites can find
        // this account. Best effort: a directory write must never undo a
        // verification that already succeeded.
        userRepo.getCurrentUser()?.let { user ->
            if (user.phoneNumber.isNotBlank()) {
                linkRepo.publishDirectoryEntry(user.phoneNumber, uid, user.name)
            }
        }
    }

    // The text-message route, where the code did come through this app and sits
    // in the user's own document until it is consumed.
    suspend operator fun invoke(uid: String, inputCode: String): Result<Unit> = runCatching {
        val (stored, createdAtMillis) = userRepo.getPhoneOtp(uid).getOrThrow()
            ?: error("No verification code found — request a new one")
        if (createdAtMillis > 0 && System.currentTimeMillis() - createdAtMillis > OTP_VALIDITY_MS) {
            error("This code has expired (valid for 1 hour) — request a new one")
        }
        if (stored != inputCode.trim()) error("Incorrect code. Please try again.")
        confirmVerified(uid).getOrThrow()
    }
}
