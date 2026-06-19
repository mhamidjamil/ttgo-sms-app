package com.spotwire.app.domain.usecase.quota

import com.spotwire.app.domain.repository.UserRepository

class DecrementQuotaUseCase(private val repo: UserRepository) {
    suspend operator fun invoke(uid: String) = repo.decrementRemainingQuota(uid)
}
