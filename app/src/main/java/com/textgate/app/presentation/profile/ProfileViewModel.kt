package com.textgate.app.presentation.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.OtpChannel
import com.textgate.app.domain.repository.ThrottleRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import com.textgate.app.domain.usecase.auth.SendEmailVerificationUseCase
import com.textgate.app.domain.usecase.auth.ConfirmEmailVerifiedUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ProfileVM"

data class ProfileUiState(
    val user: User? = null,
    val effectiveQuota: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,

    // Email verification — Firebase emails a link, the user opens it, then
    // taps to confirm. There is no code to type.
    val isSendingEmailCode: Boolean = false,
    val emailCodeSent: Boolean = false,
    val isVerifyingEmail: Boolean = false,
    val emailVerifyError: String? = null,
    // Seconds until "Send Code" may be tapped again (persisted anti-spam state).
    val emailCooldownSeconds: Int = 0,
    // One-shot toast text; the screen shows it and calls clearToast().
    val toastMessage: String? = null,
    // Which half of the History page opens first.
    val defaultHistoryTab: String = PreferencesDataSource.HISTORY_TAB_AUTOMATED,
)

class ProfileViewModel(
    private val userRepo: UserRepository,
    private val getEffectiveQuota: GetEffectiveQuotaUseCase,
    private val sendEmailVerification: SendEmailVerificationUseCase,
    private val confirmEmailVerified: ConfirmEmailVerifiedUseCase,
    private val throttle: ThrottleRepository,
    private val waRepo: WhatsAppRepository,
    private val prefs: PreferencesDataSource,
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
            _uiState.value = ProfileUiState(
                user = user,
                effectiveQuota = quota,
                isLoading = false,
                defaultHistoryTab = prefs.getDefaultHistoryTab(),
            )
        }
    }

    fun setDefaultHistoryTab(tab: String) {
        viewModelScope.launch {
            prefs.setDefaultHistoryTab(tab)
            _uiState.value = _uiState.value.copy(defaultHistoryTab = tab)
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingEmailCode = true, emailVerifyError = null)
            sendEmailVerification()
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSendingEmailCode = false,
                        emailCodeSent = true,
                        toastMessage = "Verification email sent",
                    )
                    startCooldownTicker()
                }
                .onFailure {
                    Log.w(TAG, "verification email failed: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        isSendingEmailCode = false,
                        emailVerifyError = it.message ?: "Could not send the email",
                    )
                }
        }
    }

    // Asks Firebase whether the emailed link has been opened yet.
    fun verifyEmailCode() {
        val user = _uiState.value.user ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isVerifyingEmail = true, emailVerifyError = null)
            confirmEmailVerified()
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
