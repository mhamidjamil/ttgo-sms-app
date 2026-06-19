package com.spotwire.app.domain.usecase.auth

import com.spotwire.app.domain.repository.OtpChannel
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.UserRepository

/**
 * Asks Firebase to email the verification link. Firebase owns the sending, so
 * the app carries no mail credential — a credential compiled into a public app
 * is a public credential. Keeps the same one-minute cooldown as the phone code
 * so the button cannot be leaned on.
 */
class SendEmailVerificationUseCase(
    private val userRepo: UserRepository,
    private val throttle: ThrottleRepository,
) {
    suspend operator fun invoke(): Result<Unit> = runCatching {
        val waitSec = throttle.otpCooldownRemaining(OtpChannel.EMAIL)
        if (waitSec > 0) error("Please wait ${waitSec}s before asking for another email")
        userRepo.sendEmailVerification().getOrThrow()
        throttle.markOtpSent(OtpChannel.EMAIL)
    }
}
