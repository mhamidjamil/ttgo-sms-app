package com.textgate.app.domain.usecase.location

import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.repository.SmsRepository
import com.textgate.app.domain.repository.UserRepository

class RecordArrivalUseCase(
    private val userRepo: UserRepository,
    private val smsRepo: SmsRepository,
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

        smsRepo.enqueueAutoArrivalSms(
            uid = uid,
            phoneNumber = guardianNumber,
            message = "${user.name} arrived at $label",
            location = location,
            routineTriggered = routineTriggered,
        ).getOrThrow()

        userRepo.recordArrival(uid, location, today, DateUtils.currentTimeHHmm()).getOrThrow()
    }
}
