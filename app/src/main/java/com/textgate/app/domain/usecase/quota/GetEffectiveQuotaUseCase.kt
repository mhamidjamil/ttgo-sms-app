package com.textgate.app.domain.usecase.quota

import com.textgate.app.domain.model.User

class GetEffectiveQuotaUseCase {
    // Phone verification gates sending entirely:
    //   phone verified   → full assigned quota (e.g. 10 SMS/day from the device doc)
    //   phone unverified → 0 (must verify the number before sending anything —
    //                      messages carry a "Sent by <number>" signature, so an
    //                      unverified sender identity is never allowed)
    // Email verification does NOT affect the SMS quota; it is required only for
    // WhatsApp linking and admin contact.
    operator fun invoke(user: User): Int =
        if (user.phoneVerified) user.assignedQuota else 0
}
