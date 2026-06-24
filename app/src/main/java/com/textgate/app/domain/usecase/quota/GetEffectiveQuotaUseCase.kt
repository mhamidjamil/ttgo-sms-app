package com.textgate.app.domain.usecase.quota

import com.textgate.app.BuildConfig
import com.textgate.app.domain.model.User

class GetEffectiveQuotaUseCase {
    // both verified  → full assigned quota (e.g. 10 SMS/day)
    // one verified   → partial quota     (e.g.  4 SMS/day)
    // none verified  → minimum quota     (e.g.  2 SMS/day)
    operator fun invoke(user: User): Int = when {
        user.emailVerified && user.phoneVerified -> user.assignedQuota
        user.emailVerified || user.phoneVerified -> BuildConfig.PARTIAL_VERIFIED_QUOTA
        else -> BuildConfig.UNVERIFIED_QUOTA
    }
}
