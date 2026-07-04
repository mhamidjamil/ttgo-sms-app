package com.textgate.app.domain.usecase.auth

import com.textgate.app.core.mail.Mailer
import com.textgate.app.domain.repository.OtpChannel
import com.textgate.app.domain.repository.ThrottleRepository
import com.textgate.app.domain.repository.UserRepository

class SendEmailOtpUseCase(
    private val userRepo: UserRepository,
    private val mailer: Mailer,
    private val throttle: ThrottleRepository,
) {
    // Mirrors SendPhoneOtpUseCase: generates a 6-digit OTP, stores it with a
    // creation time (codes expire after 1 hour), and delivers it — over SMTP
    // instead of the TTGO SMS queue. Replaces the Firebase link email, which
    // never reliably reached inboxes.
    suspend operator fun invoke(uid: String, email: String): Result<Unit> = runCatching {
        val waitSec = throttle.otpCooldownRemaining(OtpChannel.EMAIL)
        if (waitSec > 0) error("Please wait ${waitSec}s before requesting another code")
        require(email.isNotBlank()) { "No email address on this account" }
        val otp = (100000..999999).random().toString()
        userRepo.saveEmailOtp(uid, otp).getOrThrow()
        mailer.send(
            to = email,
            subject = "TextGate verification code: $otp",
            body = """
                Your TextGate email verification code is: $otp

                Enter it in the app under Profile -> Verify Email.
                The code is valid for 1 hour. If you didn't request it, ignore this email.
            """.trimIndent(),
        ).getOrThrow()
        throttle.markOtpSent(OtpChannel.EMAIL)
    }
}
