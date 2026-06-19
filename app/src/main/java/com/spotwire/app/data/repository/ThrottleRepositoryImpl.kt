package com.spotwire.app.data.repository

import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.domain.repository.OtpChannel
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.ThrottleRepository.Companion.OTP_COOLDOWN_SECONDS

class ThrottleRepositoryImpl(
    private val prefs: PreferencesDataSource,
) : ThrottleRepository {

    private val OtpChannel.key get() = name.lowercase()

    override suspend fun otpCooldownRemaining(channel: OtpChannel): Int {
        val sentAt = prefs.getOtpSentAt(channel.key) ?: return 0
        val elapsedSec = (System.currentTimeMillis() - sentAt) / 1000L
        // A clock jumped backwards (elapsed < 0) shouldn't lock the button for days.
        if (elapsedSec < 0) return 0
        return (OTP_COOLDOWN_SECONDS - elapsedSec).coerceAtLeast(0L).toInt()
    }

    override suspend fun markOtpSent(channel: OtpChannel) {
        prefs.setOtpSentAt(channel.key, System.currentTimeMillis())
    }

    override suspend fun canRequestMoreSmsToday(): Boolean =
        prefs.getQuotaRequestDate() != DateUtils.todayString()

    override suspend fun markSmsRequestSent() {
        prefs.setQuotaRequestDate(DateUtils.todayString())
    }
}
