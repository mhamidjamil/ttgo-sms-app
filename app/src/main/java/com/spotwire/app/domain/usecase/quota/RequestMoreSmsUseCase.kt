package com.spotwire.app.domain.usecase.quota

import com.spotwire.app.domain.model.User
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.UserRepository

/**
 * Files a quota-increase request for the admin to act on. It is written to the
 * user's own document rather than emailed: sending mail from the app would mean
 * shipping a mailbox password inside a public download, and anyone could read
 * it straight back out of the APK.
 */
class RequestMoreSmsUseCase(
    private val userRepo: UserRepository,
    private val throttle: ThrottleRepository,
) {
    suspend operator fun invoke(user: User, note: String): Result<Unit> {
        if (!throttle.canRequestMoreSmsToday()) {
            return Result.failure(IllegalStateException("You've already sent a request today — try again tomorrow"))
        }
        val result = userRepo.requestMoreSms(user.uid, note.trim(), user.assignedQuota)
        if (result.isSuccess) throttle.markSmsRequestSent()
        return result
    }
}
