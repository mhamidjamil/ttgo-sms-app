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
    val error: String? = null,
    val success: Boolean = false,
    val phoneNumber: String = "",
    val resendSuccess: Boolean = false,
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

    fun verify(code: String) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
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

    fun resend() {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        val phone = _uiState.value.phoneNumber
        if (phone.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, resendSuccess = false)
            sendPhoneOtp(uid, phone)
                .onSuccess { _uiState.value = _uiState.value.copy(isLoading = false, resendSuccess = true) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = it.message ?: "Failed to resend code",
                    )
                }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}
