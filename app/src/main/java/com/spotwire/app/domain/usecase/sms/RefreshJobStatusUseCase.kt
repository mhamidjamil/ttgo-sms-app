package com.spotwire.app.domain.usecase.sms

import com.spotwire.app.domain.model.HistoryEntry
import com.spotwire.app.domain.repository.SmsRepository

class RefreshJobStatusUseCase(private val repo: SmsRepository) {
    suspend operator fun invoke(uid: String, entry: HistoryEntry): Result<Unit> {
        val job = repo.fetchJobStatus(entry.jobId.ifBlank { entry.jobPhoneKey })
            .getOrElse { return Result.failure(it) }
        // Each send now owns its job doc, so this only still matters for entries
        // written while jobs were keyed by phone number and could be overwritten.
        if (job.enqueBy == entry.enqueBy) {
            return repo.updateHistoryStatus(uid, entry.id, job.status.firestoreValue)
        }
        return Result.success(Unit)
    }
}
