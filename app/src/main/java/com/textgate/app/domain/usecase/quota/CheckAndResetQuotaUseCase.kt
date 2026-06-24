package com.textgate.app.domain.usecase.quota

import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.UserRepository

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
