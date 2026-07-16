package com.textgate.app.domain.usecase.auto

import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.repository.SmsRepository

// Mirrors RefreshJobStatusUseCase for the Auto page: copy the gateway job's
// current status into the user's auto_history entry — but only while the job
// doc still belongs to this arrival enqueue, which entries written before jobs
// got their own ids cannot take for granted.
class RefreshAutoJobStatusUseCase(private val repo: SmsRepository) {
    suspend operator fun invoke(uid: String, entry: AutoHistoryEntry): Result<Unit> {
        if (entry.channel != "sms") return Result.success(Unit) // WhatsApp entries are terminal
        val job = repo.fetchJobStatus(entry.jobId.ifBlank { entry.jobPhoneKey })
            .getOrElse { return Result.failure(it) }
        val expectedEnqueBy = entry.enqueBy.ifBlank { "app:$uid:arrival" }
        if (job.enqueBy == expectedEnqueBy) {
            return repo.updateAutoHistoryStatus(uid, entry.id, job.status.firestoreValue)
        }
        return Result.success(Unit)
    }
}
