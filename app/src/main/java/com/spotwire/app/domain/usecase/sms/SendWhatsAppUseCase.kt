package com.spotwire.app.domain.usecase.sms

import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.repository.SmsRepository
import com.spotwire.app.domain.repository.WhatsAppRepository

/**
 * A message sent by hand over WhatsApp, and the record of it.
 *
 * The record is written whether the send worked or not. A failure that leaves no
 * trace is a message the person believes they sent, and the whole point of the
 * history is that it tells the truth about what actually left.
 */
class SendWhatsAppUseCase(
    private val waRepo: WhatsAppRepository,
    private val smsRepo: SmsRepository,
    private val normalizer: PhoneNormalizer,
) {
    suspend operator fun invoke(
        uid: String,
        rawPhone: String,
        message: String,
        senderPhone: String,
        countryIso: String,
    ): Result<String> {
        val normalized = normalizer.normalize(rawPhone, countryIso)
            ?: return Result.failure(IllegalArgumentException("Invalid phone number: $rawPhone"))
        if (senderPhone.isBlank()) {
            return Result.failure(IllegalStateException("Verify your phone number before sending"))
        }
        val body = message + EnqueueSmsUseCase.signature(senderPhone)
        val sent = waRepo.sendMessage(normalized, body)
        val entryId = smsRepo.logWhatsAppSend(
            uid = uid,
            phoneNumber = normalized,
            message = body,
            waMessageId = sent.getOrDefault(""),
            // The gateway took it, which is not the same as delivered. It stays
            // pending until the gateway says which of the two it became.
            status = if (sent.isSuccess) "pending" else "failed",
            error = sent.exceptionOrNull()?.message.orEmpty(),
        ).getOrDefault("")
        return sent.map { entryId }
    }
}
