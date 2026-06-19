package com.spotwire.app.domain.usecase.auto

import com.spotwire.app.domain.model.AutoHistoryEntry
import com.spotwire.app.domain.repository.SmsRepository

// Re-queues a failed arrival alert on the SMS gateway. WhatsApp entries have no
// gateway job behind them (a 202-queued send has no delivery receipt), so they
// are terminal and cannot be retried.
class RetryAutoArrivalUseCase(private val repo: SmsRepository) {
    suspend operator fun invoke(uid: String, entry: AutoHistoryEntry): Result<Unit> {
        if (entry.channel != "sms") {
            return Result.failure(IllegalStateException("WhatsApp alerts cannot be retried"))
        }
        return repo.retryAutoArrivalSms(uid, entry.id, entry.jobPhoneKey, entry.message)
    }
}
