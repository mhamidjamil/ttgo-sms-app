package com.textgate.app.domain.usecase.quota

import com.textgate.app.domain.repository.UserRepository

class DecrementQuotaUseCase(private val repo: UserRepository) {
    suspend operator fun invoke(uid: String) = repo.decrementRemainingQuota(uid)
}
