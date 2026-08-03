package com.textgate.app.domain.usecase.location

import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.model.PlaceContact
import com.textgate.app.domain.repository.SmsRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.quota.DecrementQuotaUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import com.textgate.app.domain.usecase.sms.EnqueueSmsUseCase

/**
 * "I am here, tell these people" — pressed by the user instead of fired by the
 * arrival service. It goes down the same road as a message typed on the Send
 * page: it lands in manual history and it costs daily quota, one message per
 * recipient. Returns how many were queued, which can be fewer than asked for
 * when the day's quota runs out part way through.
 */
class SendLocationNowUseCase(
    private val userRepo: UserRepository,
    private val smsRepo: SmsRepository,
    private val getEffectiveQuota: GetEffectiveQuotaUseCase,
    private val decrementQuota: DecrementQuotaUseCase,
) {
    companion object {
        // Tells the receiver a person pressed send, as opposed to the arrival
        // alert that goes out on its own. Plain ASCII, same as the signature.
        const val MANUAL_SUFFIX = " (via manual trigger)"
    }

    suspend operator fun invoke(
        uid: String,
        placeId: String,
        recipients: List<PlaceContact>,
    ): Result<Int> = runCatching {
        if (recipients.isEmpty()) error("Pick at least one person to message")
        val user = userRepo.getCurrentUser() ?: error("User not found")
        val place = user.places.find { it.id == placeId } ?: error("Place not found")
        if (!user.phoneVerified || user.phoneNumber.isBlank()) {
            error("Verify your phone number before sending")
        }
        val sentToday = (user.assignedQuota - user.remainingQuota).coerceAtLeast(0)
        val allowance = (getEffectiveQuota(user) - sentToday).coerceAtLeast(0)
        if (allowance == 0) error("Daily quota reached. Resets at midnight.")

        val label = place.label.ifBlank { placeId }
        val message = "${user.name} is at $label at ${DateUtils.currentTime12h()}" +
            EnqueueSmsUseCase.signature(user.phoneNumber) + MANUAL_SUFFIX

        var queued = 0
        var lastFailure: Throwable? = null
        recipients.take(allowance).forEach { contact ->
            smsRepo.enqueueJob(uid, contact.number, message)
                .onSuccess { queued++; decrementQuota(uid) }
                .onFailure { lastFailure = it }
        }
        if (queued == 0) throw (lastFailure ?: IllegalStateException("Nothing was queued"))
        queued
    }
}
