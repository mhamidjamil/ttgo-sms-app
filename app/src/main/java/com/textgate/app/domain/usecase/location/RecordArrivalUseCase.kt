package com.textgate.app.domain.usecase.location

import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.repository.SmsRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository

class RecordArrivalUseCase(
    private val userRepo: UserRepository,
    private val smsRepo: SmsRepository,
    private val waRepo: WhatsAppRepository,
) {
    suspend operator fun invoke(uid: String, placeId: String, routineTriggered: Boolean): Result<Unit> = runCatching {
        val user = userRepo.getCurrentUser() ?: error("User not found")
        val guardianNumber = user.guardianNumber.ifBlank { return@runCatching }
        val place = user.places.find { it.id == placeId } ?: return@runCatching

        val today = DateUtils.todayString()
        if (user.lastArrivalDateByPlace[placeId] == today) return@runCatching // one alert/day/place

        val label = place.label.ifBlank { placeId }
        // Per-place custom message, or the default arrival line.
        val message = place.message.ifBlank { "${user.name} arrived at $label" }

        // WhatsApp first when a gateway account is linked (free, no SMS quota);
        // fall back to the SMS gateway when unlinked or the send fails.
        val sentViaWhatsApp = waRepo.isLinked() &&
            waRepo.sendMessage(guardianNumber, message, user.name).isSuccess

        if (!sentViaWhatsApp) {
            smsRepo.enqueueAutoArrivalSms(
                uid = uid,
                phoneNumber = guardianNumber,
                message = message,
                location = placeId,
                routineTriggered = routineTriggered,
            ).getOrThrow()
        }

        userRepo.recordArrival(uid, placeId, today, DateUtils.currentTimeHHmm()).getOrThrow()
    }
}
