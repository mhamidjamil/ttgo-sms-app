package com.spotwire.app.domain.usecase.quota

import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.model.User

class GetEffectiveQuotaUseCase(private val normalizer: PhoneNormalizer) {
    // Text messages are sent by one device holding a Pakistani SIM, so they are
    // for Pakistani accounts and nobody else:
    //   Pakistani number, verified → the full daily allowance
    //   Pakistani number, unverified → 0, because every message is signed with
    //                                  the sender's number and an unproven
    //                                  identity may not sign anything
    //   any other country → 0, and the Send screen says why rather than showing
    //                       an allowance that could never be spent
    operator fun invoke(user: User): Int = when {
        !normalizer.isPakistaniMobile(user.phoneNumber) -> 0
        !user.phoneVerified -> 0
        else -> user.assignedQuota
    }
}
