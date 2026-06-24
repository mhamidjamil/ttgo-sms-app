package com.textgate.app.domain.usecase.auth

import com.textgate.app.domain.repository.UserRepository

class SendVerificationEmailUseCase(private val repo: UserRepository) {
    suspend operator fun invoke() = repo.sendVerificationEmail()
}
