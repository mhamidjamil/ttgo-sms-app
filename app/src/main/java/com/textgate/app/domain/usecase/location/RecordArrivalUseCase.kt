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
    suspend operator fun invoke(uid: String, location: String, routineTriggered: Boolean): Result<Unit> = runCatching {
        val user = userRepo.getCurrentUser() ?: error("User not found")
        val guardianNumber = user.guardianNumber.ifBlank { return@runCatching }

        val today = DateUtils.todayString()
        val lastDate = if (location == "home") user.lastHomeArrivalDate else user.lastOfficeArrivalDate
        if (lastDate == today) return@runCatching // one notification per day, per location

        val label = if (location == "home") {
            user.homeLabel.ifBlank { "home" }
        } else {
            user.officeLabel.ifBlank { "office" }
        }
        val message = "${user.name} arrived at $label"

        // WhatsApp first when a gateway account is linked (free, no SMS quota);
        // fall back to the SMS gateway when unlinked or the send fails
        // (disconnected session, service down, etc.).
        val sentViaWhatsApp = waRepo.isLinked() &&
            waRepo.sendMessage(guardianNumber, message, user.name).isSuccess

        if (!sentViaWhatsApp) {
            smsRepo.enqueueAutoArrivalSms(
                uid = uid,
                phoneNumber = guardianNumber,
                message = message,
                location = location,
                routineTriggered = routineTriggered,
            ).getOrThrow()
        }

        userRepo.recordArrival(uid, location, today, DateUtils.currentTimeHHmm()).getOrThrow()
    }
}
