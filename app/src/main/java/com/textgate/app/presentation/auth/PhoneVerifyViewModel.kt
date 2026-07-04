package com.textgate.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.repository.OtpChannel
import com.textgate.app.domain.repository.ThrottleRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import com.textgate.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.textgate.app.domain.usecase.auth.VerifyPhoneOtpUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    // Seconds until "Send Code" may be tapped again (anti-spam; persisted, so
    // it survives app restarts and covers the signup auto-send).
    val cooldownSeconds: Int = 0,
    // One-shot toast text; the screen shows it and calls clearToast().
    val toastMessage: String? = null,
)

class PhoneVerifyViewModel(
    private val userRepo: UserRepository,
    private val verifyPhoneOtp: VerifyPhoneOtpUseCase,
    private val sendPhoneOtp: SendPhoneOtpUseCase,
    private val throttle: ThrottleRepository,
    private val waRepo: WhatsAppRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneVerifyUiState())
    val uiState: StateFlow<PhoneVerifyUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    init {
        loadPhone()
        startCooldownTicker()
    }

    private fun loadPhone() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            _uiState.value = _uiState.value.copy(phoneNumber = user?.phoneNumber ?: "")
        }
    }

    private fun startCooldownTicker() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var remaining = throttle.otpCooldownRemaining(OtpChannel.PHONE)
            while (remaining > 0) {
                _uiState.value = _uiState.value.copy(cooldownSeconds = remaining)
                delay(1_000)
                remaining = throttle.otpCooldownRemaining(OtpChannel.PHONE)
            }
            _uiState.value = _uiState.value.copy(cooldownSeconds = 0)
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
                        toastMessage = "OTP Sent!",
                    )
                    startCooldownTicker()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = it.message ?: "Failed to send code",
                    )
                }
        }
    }

    fun clearToast() { _uiState.value = _uiState.value.copy(toastMessage = null) }

    fun verify(code: String) {
        val uid = userRepo.currentFirebaseUser()?.uid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(error = "Not signed in — please log in again")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            verifyPhoneOtp(uid, code)
                .onSuccess {
                    // Best-effort SSO: once BOTH verifications are done this
                    // provisions the WhatsApp gateway account in the background.
                    // Failures are silent here — the WhatsApp screen surfaces them.
                    launch { waRepo.ensureProvisioned() }
                    _uiState.value = PhoneVerifyUiState(success = true)
                }
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
