package com.textgate.app.domain.repository

enum class OtpChannel { PHONE, EMAIL }

// Local (device-side) anti-spam state, persisted across app restarts.
interface ThrottleRepository {
    companion object {
        const val OTP_COOLDOWN_SECONDS = 60
    }

    // Seconds until another OTP may be requested on this channel; 0 = allowed now.
    suspend fun otpCooldownRemaining(channel: OtpChannel): Int
    suspend fun markOtpSent(channel: OtpChannel)

    // Quota-increase request is limited to once per calendar day.
    suspend fun canRequestMoreSmsToday(): Boolean
    suspend fun markSmsRequestSent()
}
