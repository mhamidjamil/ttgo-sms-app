package com.textgate.app.domain.usecase.quota

import com.textgate.app.BuildConfig
import com.textgate.app.core.mail.Mailer
import com.textgate.app.domain.model.User

class RequestMoreSmsUseCase(private val mailer: Mailer) {
    // Emails the admin a quota-increase request carrying the user's identity
    // (email + verified phone) so the admin can raise assigned_quota /
    // free_sms_quota in Firestore.
    suspend operator fun invoke(user: User): Result<Unit> {
        val admin = BuildConfig.ADMIN_EMAIL
        if (admin.isBlank()) {
            return Result.failure(IllegalStateException("Admin email is not configured in this build"))
        }
        return mailer.send(
            to = admin,
            subject = "TextGate: quota increase request",
            body = """
                A TextGate user is requesting a higher daily SMS quota.

                Email: ${user.email}
                Phone: ${user.phoneNumber.ifBlank { "(not set)" }}
                Current daily quota: ${user.assignedQuota}

                To grant more, raise assigned_quota on ttgo_users/${user.uid}
                (or free_sms_quota on the device doc for all users).
            """.trimIndent(),
        )
    }
}
