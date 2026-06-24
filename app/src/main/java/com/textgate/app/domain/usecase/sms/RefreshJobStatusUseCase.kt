package com.textgate.app.domain.usecase.sms

import com.textgate.app.domain.model.HistoryEntry
import com.textgate.app.domain.repository.SmsRepository

class RefreshJobStatusUseCase(private val repo: SmsRepository) {
    suspend operator fun invoke(uid: String, entry: HistoryEntry): Result<Unit> {
        val job = repo.fetchJobStatus(entry.jobPhoneKey).getOrElse { return Result.failure(it) }
        // Only update if this job doc still belongs to this user's enqueue
        if (job.enqueBy == entry.enqueBy) {
            return repo.updateHistoryStatus(uid, entry.id, job.status.firestoreValue)
        }
        return Result.success(Unit)
    }
}
