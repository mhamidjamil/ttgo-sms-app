package com.textgate.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.textgate.app.domain.usecase.auth.VerifyPhoneOtpUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhoneVerifyUiState(
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val phoneNumber: String = "",
    // True once a code has actually been enqueued in this session — gates the
    // "code was sent" copy in the UI (it used to claim a code was sent when
    // nothing had been enqueued at all).
    val codeSent: Boolean = false,
)

class PhoneVerifyViewModel(
    private val userRepo: UserRepository,
    private val verifyPhoneOtp: VerifyPhoneOtpUseCase,
    private val sendPhoneOtp: SendPhoneOtpUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneVerifyUiState())
    val uiState: StateFlow<PhoneVerifyUiState> = _uiState.asStateFlow()

    init { loadPhone() }

    private fun loadPhone() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            _uiState.value = _uiState.value.copy(phoneNumber = user?.phoneNumber ?: "")
        }
    }

    // Explicit send — called from the "Send Code" / "Resend Code" buttons with
    // whatever phone number is currently in the input field. Never a silent
    // no-op: every failure path surfaces an error message.
    fun sendCode(phone: String) {
        val uid = userRepo.currentFirebaseUser()?.uid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(error = "Not signed in — please log in again")
            return
        }
        if (phone.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter your phone number first")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            sendPhoneOtp(uid, phone)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        codeSent = true,
                        phoneNumber = phone,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = it.message ?: "Failed to send code",
                    )
                }
        }
    }

    fun verify(code: String) {
        val uid = userRepo.currentFirebaseUser()?.uid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(error = "Not signed in — please log in again")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            verifyPhoneOtp(uid, code)
                .onSuccess { _uiState.value = PhoneVerifyUiState(success = true) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = it.message ?: "Verification failed",
                    )
                }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}
