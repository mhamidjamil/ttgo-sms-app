package com.spotwire.app.domain.usecase.auth

import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.repository.OtpChannel
import com.spotwire.app.domain.repository.SmsRepository
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.UserRepository

class SendPhoneOtpUseCase(
    private val userRepo: UserRepository,
    private val smsRepo: SmsRepository,
    private val phoneNormalizer: PhoneNormalizer,
    private val throttle: ThrottleRepository,
) {
    // Generates a 6-digit OTP, stores it (with creation time — codes expire after
    // 1 hour), and enqueues a priority OTP SMS to the user's own phone via the
    // TTGO gateway (kind:"otp" → device sends it before regular jobs).
    suspend operator fun invoke(uid: String, phoneNumber: String): Result<Unit> = runCatching {
        val waitSec = throttle.otpCooldownRemaining(OtpChannel.PHONE)
        if (waitSec > 0) error("Please wait ${waitSec}s before requesting another code")
        val normalized = phoneNormalizer.normalize(phoneNumber)
            ?: error("Enter a valid Pakistani mobile number (e.g. 03001234567)")
        val otp = (100000..999999).random().toString()
        userRepo.savePhoneNumber(uid, normalized).getOrThrow()
        userRepo.savePhoneOtp(uid, otp).getOrThrow()
        smsRepo.enqueueOtpSms(
            uid,
            normalized,
            "Spotwire verification code: $otp\nValid for 1 hour. Do not share this code.",
        ).getOrThrow()
        throttle.markOtpSent(OtpChannel.PHONE)
    }
}
