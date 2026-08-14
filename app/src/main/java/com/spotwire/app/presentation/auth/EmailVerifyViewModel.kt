package com.spotwire.app.presentation.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.domain.repository.OtpChannel
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.usecase.auth.ConfirmEmailVerifiedUseCase
import com.spotwire.app.domain.usecase.auth.SendEmailVerificationUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "EmailVerifyVM"

data class EmailVerifyUiState(
    val email: String = "",
    val isSending: Boolean = false,
    val isChecking: Boolean = false,
    val sent: Boolean = false,
    val verified: Boolean = false,
    val cooldownSeconds: Int = 0,
    val error: String? = null,
    val toastMessage: String? = null,
)

/**
 * Confirming the account by email, which is how anyone outside Pakistan gets in:
 * the text code needs the one device with a Pakistani SIM, and their own number
 * is proven later and for free, when they connect their WhatsApp gateway.
 *
 * Firebase sends the mail and holds its own credentials, so nothing here carries
 * a mailbox password. That is deliberate and must stay that way.
 */
class EmailVerifyViewModel(
    private val userRepo: UserRepository,
    private val sendEmailVerification: SendEmailVerificationUseCase,
    private val confirmEmailVerified: ConfirmEmailVerifiedUseCase,
    private val throttle: ThrottleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailVerifyUiState())
    val uiState: StateFlow<EmailVerifyUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    init {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            _uiState.value = _uiState.value.copy(
                email = user?.email.orEmpty(),
                verified = user?.emailVerified == true,
                // Sign-up already sent one, so the screen opens saying so rather
                // than inviting a second mail nobody needs.
                sent = true,
            )
            startCooldownTicker()
        }
    }

    fun resend() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            sendEmailVerification()
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSending = false, sent = true, toastMessage = "Verification email sent",
                    )
                    startCooldownTicker()
                }
                .onFailure {
                    Log.w(TAG, "verification email failed: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        isSending = false, error = it.message ?: "Could not send the email",
                    )
                }
        }
    }

    fun check() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChecking = true, error = null)
            confirmEmailVerified()
                .onSuccess {
                    Log.i(TAG, "account confirmed by email")
                    _uiState.value = _uiState.value.copy(isChecking = false, verified = true)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isChecking = false, error = it.message ?: "Not verified yet",
                    )
                }
        }
    }

    private fun startCooldownTicker() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var remaining = throttle.otpCooldownRemaining(OtpChannel.EMAIL)
            while (remaining > 0) {
                _uiState.value = _uiState.value.copy(cooldownSeconds = remaining)
                delay(1_000)
                remaining = throttle.otpCooldownRemaining(OtpChannel.EMAIL)
            }
            _uiState.value = _uiState.value.copy(cooldownSeconds = 0)
        }
    }

    fun clearToast() { _uiState.value = _uiState.value.copy(toastMessage = null) }
}
