package com.textgate.app.domain.usecase.auth

import com.textgate.app.domain.repository.UserRepository

class VerifyPhoneOtpUseCase(private val userRepo: UserRepository) {
    suspend operator fun invoke(uid: String, inputCode: String): Result<Unit> = runCatching {
        val stored = userRepo.getPhoneOtp(uid).getOrThrow()
            ?: error("No verification code found — request a new one")
        if (stored != inputCode.trim()) error("Incorrect code. Please try again.")
        userRepo.markPhoneVerified(uid).getOrThrow()
    }
}
