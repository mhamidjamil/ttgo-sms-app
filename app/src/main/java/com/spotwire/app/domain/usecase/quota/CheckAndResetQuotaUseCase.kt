package com.spotwire.app.domain.usecase.quota

import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.domain.model.User
import com.spotwire.app.domain.repository.UserRepository

class CheckAndResetQuotaUseCase(private val repo: UserRepository) {
    suspend operator fun invoke(user: User): Result<User> {
        val today = DateUtils.todayString()
        return if (user.lastQuotaResetDate != today) {
            repo.updateQuotaReset(user.uid, user.assignedQuota, today)
                .map { user.copy(remainingQuota = user.assignedQuota, lastQuotaResetDate = today) }
        } else {
            Result.success(user)
        }
    }
}
