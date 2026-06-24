package com.textgate.app.domain.usecase.auth

import com.textgate.app.domain.repository.SmsRepository
import com.textgate.app.domain.repository.UserRepository

class SendPhoneOtpUseCase(
    private val userRepo: UserRepository,
    private val smsRepo: SmsRepository,
) {
    // Generates a 6-digit OTP, stores it in Firestore, and enqueues an OTP SMS
    // to the user's own phone via the TTGO gateway.
    // OTP does not expire — user can verify anytime from the app.
    suspend operator fun invoke(uid: String, phoneNumber: String): Result<Unit> = runCatching {
        val otp = (100000..999999).random().toString()
        userRepo.savePhoneNumber(uid, phoneNumber).getOrThrow()
        userRepo.savePhoneOtp(uid, otp).getOrThrow()
        smsRepo.enqueueOtpSms(uid, phoneNumber, "Your TextGate verification code: $otp").getOrThrow()
    }
}
