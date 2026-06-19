package com.spotwire.app.domain.usecase.auth

import com.spotwire.app.domain.repository.UserRepository

class SignUpUseCase(private val repo: UserRepository) {
    suspend operator fun invoke(email: String, password: String, name: String) =
        repo.signUp(email.trim(), password, name.trim())
}
