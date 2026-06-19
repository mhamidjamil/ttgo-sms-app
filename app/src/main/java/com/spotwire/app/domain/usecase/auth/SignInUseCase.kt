package com.spotwire.app.domain.usecase.auth

import com.spotwire.app.domain.repository.UserRepository

class SignInUseCase(private val repo: UserRepository) {
    suspend operator fun invoke(email: String, password: String) =
        repo.signIn(email.trim(), password)
}
