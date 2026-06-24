package com.textgate.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.core.utils.PhoneNormalizer
import com.textgate.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.textgate.app.domain.usecase.auth.SendVerificationEmailUseCase
import com.textgate.app.domain.usecase.auth.SignInUseCase
import com.textgate.app.domain.usecase.auth.SignUpUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val verificationSent: Boolean = false,
    val navigateToPhoneVerify: Boolean = false,
)

class AuthViewModel(
    private val signIn: SignInUseCase,
    private val signUp: SignUpUseCase,
    private val sendVerification: SendVerificationEmailUseCase,
    private val sendPhoneOtp: SendPhoneOtpUseCase,
    private val phoneNormalizer: PhoneNormalizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = AuthUiState(error = "Email and password are required")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            signIn(email, password)
                .onSuccess { _uiState.value = AuthUiState(success = true) }
                .onFailure { _uiState.value = AuthUiState(error = it.message ?: "Login failed") }
        }
    }

    fun register(email: String, password: String, name: String, phone: String) {
        if (email.isBlank() || password.isBlank() || name.isBlank() || phone.isBlank()) {
            _uiState.value = AuthUiState(error = "All fields are required")
            return
        }
        if (password.length < 6) {
            _uiState.value = AuthUiState(error = "Password must be at least 6 characters")
            return
        }
        val normalizedPhone = phoneNormalizer.normalize(phone)
        if (normalizedPhone == null) {
            _uiState.value = AuthUiState(error = "Enter a valid Pakistani number (e.g. 03001234567)")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            signUp(email, password, name)
                .onSuccess { user ->
                    sendVerification()
                    // Best-effort OTP send — failure is surfaced on the verify screen, not here
                    sendPhoneOtp(user.uid, normalizedPhone)
                    _uiState.value = AuthUiState(
                        success = true,
                        verificationSent = true,
                        navigateToPhoneVerify = true,
                    )
                }
                .onFailure { _uiState.value = AuthUiState(error = it.message ?: "Sign-up failed") }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}
