package com.spotwire.app.domain.usecase.sms

import com.spotwire.app.domain.model.HistoryEntry
import com.spotwire.app.domain.repository.SmsRepository
import com.spotwire.app.domain.repository.WhatsAppRepository

/**
 * Where a sent message actually got to. Two routes, two sources of truth: the
 * device writes its verdict onto the job document, and the gateway keeps its own
 * per-message trail. Neither is "we handed it over, so it arrived", which is
 * what the app used to show for WhatsApp.
 */
class RefreshJobStatusUseCase(
    private val repo: SmsRepository,
    private val waRepo: WhatsAppRepository,
) {
    suspend operator fun invoke(uid: String, entry: HistoryEntry): Result<Unit> {
        if (entry.isWhatsApp) return refreshWhatsApp(uid, listOf(entry))
        val job = repo.fetchJobStatus(entry.jobId.ifBlank { entry.jobPhoneKey })
            .getOrElse { return Result.failure(it) }
        // Each send now owns its job doc, so this only still matters for entries
        // written while jobs were keyed by phone number and could be overwritten.
        if (job.enqueBy == entry.enqueBy) {
            return repo.updateHistoryStatus(uid, entry.id, job.status.firestoreValue)
        }
        return Result.success(Unit)
    }

    /**
     * One call covers every waiting row, because the gateway answers with its
     * recent trail rather than one message at a time.
     */
    suspend fun refreshWhatsApp(uid: String, entries: List<HistoryEntry>): Result<Unit> = runCatching {
        val byId = entries.filter { it.waMessageId.isNotBlank() }.associateBy { it.waMessageId }
        if (byId.isEmpty()) return@runCatching
        val statuses = waRepo.deliveryStatuses(byId.keys.toList()).getOrThrow()
        byId.forEach { (messageId, entry) ->
            val delivery = statuses[messageId] ?: return@forEach
            val status = when (delivery.status) {
                "sent" -> "sent"
                "failed", "cancelled" -> "failed"
                "sending" -> "in_progress"
                else -> "pending"
            }
            if (status != entry.status.firestoreValue || delivery.error.orEmpty() != entry.error) {
                repo.updateWhatsAppHistory(uid, entry.id, status, delivery.error.orEmpty())
            }
        }
    }
}
