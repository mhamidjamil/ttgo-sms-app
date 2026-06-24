package com.textgate.app.domain.usecase.sms

import com.textgate.app.core.utils.PhoneNormalizer
import com.textgate.app.domain.repository.SmsRepository

class EnqueueSmsUseCase(
    private val smsRepo: SmsRepository,
    private val normalizer: PhoneNormalizer,
) {
    suspend operator fun invoke(
        uid: String,
        rawPhone: String,
        message: String,
    ): Result<String> {
        val normalized = normalizer.normalize(rawPhone)
            ?: return Result.failure(IllegalArgumentException("Invalid phone number: $rawPhone"))
        return smsRepo.enqueueJob(uid, normalized, message)
    }
}
