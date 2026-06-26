package com.textgate.app.domain.usecase.auth

import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.UserRepository

class VerifyPhoneOtpUseCase(
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
) {

    companion object {
        // Codes are valid for 1 hour after being sent.
        const val OTP_VALIDITY_MS = 60L * 60L * 1000L
    }

    suspend operator fun invoke(uid: String, inputCode: String): Result<Unit> = runCatching {
        val (stored, createdAtMillis) = userRepo.getPhoneOtp(uid).getOrThrow()
            ?: error("No verification code found — request a new one")
        if (createdAtMillis > 0 && System.currentTimeMillis() - createdAtMillis > OTP_VALIDITY_MS) {
            error("This code has expired (valid for 1 hour) — request a new one")
        }
        if (stored != inputCode.trim()) error("Incorrect code. Please try again.")
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
}
