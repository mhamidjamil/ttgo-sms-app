package com.spotwire.app.domain.usecase.sms

import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.repository.SmsRepository

class EnqueueSmsUseCase(
    private val smsRepo: SmsRepository,
    private val normalizer: PhoneNormalizer,
) {
    companion object {
        // User-editable part of the message. 90 chars + the ~38-char signature
        // stays well inside one 160-char GSM-7 segment.
        const val MAX_USER_MESSAGE_CHARS = 90

        // Accountability signature appended to every manual SMS: the gateway
        // number is shared, so each message names the VERIFIED sender it was
        // sent by. Plain ASCII only (an em-dash would force UCS-2 encoding and
        // split the SMS into segments).
        fun signature(senderPhone: String) = "\n- Sent by $senderPhone via Spotwire"
    }

    suspend operator fun invoke(
        uid: String,
        rawPhone: String,
        message: String,
        senderPhone: String,
        countryIso: String,
    ): Result<String> {
        val normalized = normalizer.normalize(rawPhone, countryIso)
            ?: return Result.failure(IllegalArgumentException("Invalid phone number: $rawPhone"))
        // One device sends these and its SIM is Pakistani, so it physically
        // cannot text anywhere else. WhatsApp is the route for everyone abroad.
        if (!normalizer.isPakistaniMobile(normalized)) {
            return Result.failure(
                IllegalArgumentException("Text messages only reach Pakistani mobiles. Use WhatsApp for $normalized.")
            )
        }
        if (senderPhone.isBlank()) {
            return Result.failure(IllegalStateException("Verify your phone number before sending"))
        }
        if (message.length > MAX_USER_MESSAGE_CHARS) {
            return Result.failure(
                IllegalArgumentException("Message too long (max $MAX_USER_MESSAGE_CHARS characters)")
            )
        }
        return smsRepo.enqueueJob(uid, normalized, message + signature(senderPhone))
    }
}
