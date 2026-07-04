package com.textgate.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.auth.SendEmailOtpUseCase
import com.textgate.app.domain.usecase.auth.VerifyEmailOtpUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val user: User? = null,
    val effectiveQuota: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,

    // Email verification (OTP over SMTP — same flow as phone)
    val isSendingEmailCode: Boolean = false,
    val emailCodeSent: Boolean = false,
    val isVerifyingEmail: Boolean = false,
    val emailVerifyError: String? = null,
)

class ProfileViewModel(
    private val userRepo: UserRepository,
    private val getEffectiveQuota: GetEffectiveQuotaUseCase,
    private val sendEmailOtp: SendEmailOtpUseCase,
    private val verifyEmailOtp: VerifyEmailOtpUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            // Legacy accounts that verified via the old Firebase link still get
            // noticed here; OTP-verified accounts read straight from Firestore.
            userRepo.refreshEmailVerified()
            val user = userRepo.getCurrentUser()
            val quota = user?.let { getEffectiveQuota(it) } ?: 0
            _uiState.value = ProfileUiState(user = user, effectiveQuota = quota, isLoading = false)
        }
    }

    fun sendEmailCode() {
        val user = _uiState.value.user ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingEmailCode = true, emailVerifyError = null)
            sendEmailOtp(user.uid, user.email)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSendingEmailCode = false,
                        emailCodeSent = true,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSendingEmailCode = false,
                        emailVerifyError = it.message ?: "Failed to send the code",
                    )
                }
        }
    }

    fun verifyEmailCode(code: String) {
        val user = _uiState.value.user ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isVerifyingEmail = true, emailVerifyError = null)
            verifyEmailOtp(user.uid, code)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isVerifyingEmail = false,
                        emailCodeSent = false,
                        user = user.copy(emailVerified = true),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isVerifyingEmail = false,
                        emailVerifyError = it.message ?: "Verification failed",
                    )
                }
        }
    }

    suspend fun signOut() { userRepo.signOut() }
}
