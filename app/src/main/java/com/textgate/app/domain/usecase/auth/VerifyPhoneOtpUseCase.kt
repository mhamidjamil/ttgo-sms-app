package com.textgate.app.domain.usecase.auth

import com.textgate.app.domain.repository.UserRepository

class VerifyPhoneOtpUseCase(private val userRepo: UserRepository) {

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
    }
}
