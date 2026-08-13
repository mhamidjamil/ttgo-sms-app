package com.spotwire.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.usecase.auth.SendEmailVerificationUseCase
import com.spotwire.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.spotwire.app.domain.usecase.auth.SignInUseCase
import com.spotwire.app.domain.usecase.auth.SignUpUseCase
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
    val navigateWithoutPhoneVerify: Boolean = false,
    val resetSending: Boolean = false,
    val resetMessage: String? = null,
)

class AuthViewModel(
    private val signIn: SignInUseCase,
    private val signUp: SignUpUseCase,
    private val sendEmailVerification: SendEmailVerificationUseCase,
    private val sendPhoneOtp: SendPhoneOtpUseCase,
    private val phoneNormalizer: PhoneNormalizer,
    private val userRepo: UserRepository,
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

    fun register(email: String, password: String, name: String, phone: String, countryIso: String) {
        if (email.isBlank() || password.isBlank() || name.isBlank() || phone.isBlank()) {
            _uiState.value = AuthUiState(error = "All fields are required")
            return
        }
        if (password.length < 6) {
            _uiState.value = AuthUiState(error = "Password must be at least 6 characters")
            return
        }
        val normalizedPhone = phoneNormalizer.normalize(phone, countryIso)
        if (normalizedPhone == null) {
            _uiState.value = AuthUiState(error = "Enter a valid mobile number for the country selected")
            return
        }
        // A text code can only reach a Pakistani mobile, because there is one
        // device sending them and its SIM is Pakistani. Everyone else records
        // their number and confirms the account by email instead.
        val verifiesByPhone = phoneNormalizer.isPakistaniMobile(normalizedPhone)
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            signUp(email, password, name)
                .onSuccess { user ->
                    // The number is recorded straight away, verified or not, so
                    // nothing is lost if they close the app on the next screen.
                    userRepo.savePhoneNumber(user.uid, normalizedPhone, countryIso)
                    // Best-effort — failures surface on the verify screen or the
                    // Profile banner, not here. No code is sent from here any
                    // more: the verify screen asks the gateway for one only once
                    // the person has given WhatsApp permission to reply to them.
                    sendEmailVerification()
                    _uiState.value = AuthUiState(
                        success = true,
                        verificationSent = true,
                        navigateToPhoneVerify = verifiesByPhone,
                        navigateWithoutPhoneVerify = !verifiesByPhone,
                    )
                }
                .onFailure { _uiState.value = AuthUiState(error = it.message ?: "Sign-up failed") }
        }
    }

    // Firebase decides whether a reset link actually goes out, and with email
    // enumeration protection on it never says. Promising "sent to <email>"
    // would therefore be a guess, and it would also tell a stranger which
    // addresses have an account here, so the wording stays conditional.
    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            _uiState.value = AuthUiState(error = "Type your email above first")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState(resetSending = true)
            userRepo.sendPasswordReset(email.trim())
                .onSuccess {
                    _uiState.value = AuthUiState(
                        resetMessage = "If an account uses this email, a reset link is on its way. " +
                            "Check the inbox and the spam folder.",
                    )
                }
                .onFailure {
                    _uiState.value = AuthUiState(error = it.message ?: "Could not send the reset email")
                }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}
