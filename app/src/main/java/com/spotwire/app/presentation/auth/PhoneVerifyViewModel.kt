package com.spotwire.app.presentation.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.repository.OtpChannel
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.spotwire.app.domain.usecase.auth.VerifyPhoneOtpUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PhoneVerifyVM"

data class PhoneVerifyUiState(
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val phoneNumber: String = "",
    val country: String = "",
    // True once a code has actually been enqueued in this session — gates the
    // "code was sent" copy in the UI (it used to claim a code was sent when
    // nothing had been enqueued at all).
    val codeSent: Boolean = false,
    // Seconds until "Send Code" may be tapped again (anti-spam; persisted, so
    // it survives app restarts and covers the signup auto-send).
    val cooldownSeconds: Int = 0,
    // One-shot toast text; the screen shows it and calls clearToast().
    val toastMessage: String? = null,
    // The code rides the one TTGO device, whose SIM is Pakistani, so a number
    // anywhere else has nothing to receive it. Those accounts are confirmed by
    // email instead and never see this screen from sign-up.
    val canReceiveCode: Boolean = true,
)

class PhoneVerifyViewModel(
    private val userRepo: UserRepository,
    private val verifyPhoneOtp: VerifyPhoneOtpUseCase,
    private val sendPhoneOtp: SendPhoneOtpUseCase,
    private val throttle: ThrottleRepository,
    private val phoneNormalizer: PhoneNormalizer,
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
            val number = user?.phoneNumber.orEmpty()
            _uiState.value = _uiState.value.copy(
                phoneNumber = number,
                country = user?.phoneCountry.orEmpty(),
                canReceiveCode = number.isBlank() || phoneNormalizer.isPakistaniMobile(number),
            )
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
    fun sendCode(phone: String, countryIso: String) {
        val uid = userRepo.currentFirebaseUser()?.uid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(error = "Not signed in, please log in again")
            return
        }
        if (phone.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter your phone number first")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            sendPhoneOtp(uid, phone, countryIso)
                .onSuccess {
                    Log.i(TAG, "verification code queued on the device")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        codeSent = true,
                        phoneNumber = phone,
                        country = countryIso,
                        canReceiveCode = true,
                        toastMessage = "Code sent",
                    )
                    startCooldownTicker()
                }
                .onFailure {
                    Log.w(TAG, "could not send the verification code: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        canReceiveCode = phoneNormalizer.isValid(phone, countryIso) &&
                            phoneNormalizer.isPakistaniMobile(
                                phoneNormalizer.normalize(phone, countryIso).orEmpty()
                            ),
                        error = it.message ?: "Failed to send code",
                    )
                }
        }
    }

    fun clearToast() { _uiState.value = _uiState.value.copy(toastMessage = null) }

    fun verify(code: String) {
        val uid = userRepo.currentFirebaseUser()?.uid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(error = "Not signed in, please log in again")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            verifyPhoneOtp(uid, code)
                .onSuccess {
                    Log.i(TAG, "phone verified")
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
