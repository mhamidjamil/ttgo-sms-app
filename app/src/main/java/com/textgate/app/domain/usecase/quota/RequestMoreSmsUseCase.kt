package com.textgate.app.domain.usecase.quota

import com.textgate.app.BuildConfig
import com.textgate.app.core.mail.Mailer
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.ThrottleRepository

class RequestMoreSmsUseCase(
    private val mailer: Mailer,
    private val throttle: ThrottleRepository,
) {
    // Emails the admin a quota-increase request carrying the user's identity
    // (email + verified phone) plus the user's own note, so the admin can raise
    // assigned_quota / free_sms_quota in Firestore. Limited to one per day.
    suspend operator fun invoke(user: User, note: String): Result<Unit> {
        val admin = BuildConfig.ADMIN_EMAIL
        if (admin.isBlank()) {
            return Result.failure(IllegalStateException("Admin email is not configured in this build"))
        }
        if (!throttle.canRequestMoreSmsToday()) {
            return Result.failure(IllegalStateException("You've already sent a request today — try again tomorrow"))
        }
        val userNote = note.trim().ifBlank { "(no message provided)" }
        val result = mailer.send(
            to = admin,
            subject = "TextGate: quota increase request",
            body = """
                A TextGate user is requesting a higher daily SMS quota.

                Email: ${user.email}
                Phone: ${user.phoneNumber.ifBlank { "(not set)" }}
                Current daily quota: ${user.assignedQuota}

                User's message:
                $userNote

                To grant more, raise assigned_quota on ttgo_users/${user.uid}
                (or free_sms_quota on the device doc for all users).
            """.trimIndent(),
        )
        if (result.isSuccess) throttle.markSmsRequestSent()
        return result
    }
}
