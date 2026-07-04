package com.textgate.app.domain.usecase.location

import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.model.PlaceContact
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
        val place = user.places.find { it.id == placeId } ?: return@runCatching

        // Per-place contacts; empty → the default guardian number (no name).
        val recipients = place.contacts
            .filter { it.number.isNotBlank() }
            .ifEmpty { listOf(PlaceContact(name = "", number = user.guardianNumber)) }
            .filter { it.number.isNotBlank() }
            .distinctBy { it.number }
        if (recipients.isEmpty()) return@runCatching

        val today = DateUtils.todayString()
        if (user.lastArrivalDateByPlace[placeId] == today) return@runCatching // one alert/day/place

        val label = place.label.ifBlank { placeId }
        // Per-place custom message, or the default arrival line.
        val message = place.message.ifBlank { "${user.name} arrived at $label" }

        // WhatsApp first when a gateway account is linked (free, no SMS quota);
        // fall back to the SMS gateway per recipient when unlinked or the send
        // fails. A failure for one recipient must not block the others.
        val waLinked = waRepo.isLinked()
        var lastFailure: Throwable? = null
        var delivered = 0
        recipients.forEach { contact ->
            // recipientName personalizes for the RECEIVER (gateway anti-ban).
            val sentViaWhatsApp = waLinked &&
                waRepo.sendMessage(contact.number, message, contact.name.ifBlank { null }).isSuccess
            if (sentViaWhatsApp) {
                delivered++
            } else {
                smsRepo.enqueueAutoArrivalSms(
                    uid = uid,
                    phoneNumber = contact.number,
                    message = message,
                    location = placeId,
                    routineTriggered = routineTriggered,
                ).onSuccess { delivered++ }.onFailure { lastFailure = it }
            }
        }
        // Nobody reached → surface the error and leave the day unrecorded so the
        // service can retry. Partial success records the arrival, otherwise a
        // retry would double-message the recipients that already got it.
        if (delivered == 0) throw (lastFailure ?: IllegalStateException("No recipients notified"))

        userRepo.recordArrival(uid, placeId, today, DateUtils.currentTimeHHmm()).getOrThrow()
    }
}
