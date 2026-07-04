package com.textgate.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.OtpChannel
import com.textgate.app.domain.repository.ThrottleRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import com.textgate.app.domain.usecase.auth.SendEmailOtpUseCase
import com.textgate.app.domain.usecase.auth.VerifyEmailOtpUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    // Seconds until "Send Code" may be tapped again (persisted anti-spam state).
    val emailCooldownSeconds: Int = 0,
    // One-shot toast text; the screen shows it and calls clearToast().
    val toastMessage: String? = null,
)

class ProfileViewModel(
    private val userRepo: UserRepository,
    private val getEffectiveQuota: GetEffectiveQuotaUseCase,
    private val sendEmailOtp: SendEmailOtpUseCase,
    private val verifyEmailOtp: VerifyEmailOtpUseCase,
    private val throttle: ThrottleRepository,
    private val waRepo: WhatsAppRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    init {
        load()
        startCooldownTicker()
    }

    private fun startCooldownTicker() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var remaining = throttle.otpCooldownRemaining(OtpChannel.EMAIL)
            while (remaining > 0) {
                _uiState.value = _uiState.value.copy(emailCooldownSeconds = remaining)
                delay(1_000)
                remaining = throttle.otpCooldownRemaining(OtpChannel.EMAIL)
            }
            _uiState.value = _uiState.value.copy(emailCooldownSeconds = 0)
        }
    }

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

    fun updateName(newName: String) {
        val user = _uiState.value.user ?: return
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Name cannot be empty")
            return
        }
        viewModelScope.launch {
            userRepo.updateName(user.uid, trimmed)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        user = user.copy(name = trimmed),
                        toastMessage = "Name updated",
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
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
                        toastMessage = "OTP Sent!",
                    )
                    startCooldownTicker()
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
                    // Best-effort SSO provisioning (no-ops until phone is verified too).
                    launch { waRepo.ensureProvisioned() }
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

    fun clearToast() { _uiState.value = _uiState.value.copy(toastMessage = null) }

    suspend fun signOut() { userRepo.signOut() }
}
