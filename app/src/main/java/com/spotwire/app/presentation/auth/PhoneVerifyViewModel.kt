package com.spotwire.app.presentation.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.repository.OtpChannel
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.repository.WhatsAppRepository
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
    // True once a code has actually been sent in this session — gates the
    // "code was sent" copy in the UI (it used to claim a code was sent when
    // nothing had been enqueued at all).
    val codeSent: Boolean = false,
    // Seconds until another code may be asked for (anti-spam; persisted, so
    // it survives app restarts and covers the signup auto-send).
    val cooldownSeconds: Int = 0,
    // One-shot toast text; the screen shows it and calls clearToast().
    val toastMessage: String? = null,

    // ── The WhatsApp route ────────────────────────────────────────────────────
    val checkingGateway: Boolean = true,
    // null while the first check is still running.
    val gatewayUp: Boolean? = null,
    // Where to send the message and what to say, both decided by the gateway.
    val waNumber: String = "",
    val waPhrase: String = "",
    val waLink: String = "",
    // We have seen their message arrive.
    val optedIn: Boolean = false,
    // Watching for it right now, and for how much longer.
    val watching: Boolean = false,
    val watchSecondsLeft: Int = 0,
    // The watch ran its course without seeing anything. Not an error: people put
    // their phone down. It just has to say so rather than spin forever.
    val watchGaveUp: Boolean = false,

    // ── The text-message fallback ─────────────────────────────────────────────
    // Only ever offered to a Pakistani number, because one device sends these
    // and its SIM is Pakistani.
    val smsFallbackOffered: Boolean = false,
    val usingSmsFallback: Boolean = false,
)

class PhoneVerifyViewModel(
    private val userRepo: UserRepository,
    private val verifyPhoneOtp: VerifyPhoneOtpUseCase,
    private val sendPhoneOtp: SendPhoneOtpUseCase,
    private val throttle: ThrottleRepository,
    private val waRepo: WhatsAppRepository,
    private val phoneNormalizer: PhoneNormalizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneVerifyUiState())
    val uiState: StateFlow<PhoneVerifyUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null
    private var watchJob: Job? = null

    init {
        loadPhone()
        startCooldownTicker()
    }

    private fun loadPhone() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            _uiState.value = _uiState.value.copy(
                phoneNumber = user?.phoneNumber ?: "",
                country = user?.phoneCountry.orEmpty(),
            )
            checkGateway()
        }
    }

    /**
     * Asked before the WhatsApp step is offered at all, so nobody is walked into
     * a flow that cannot finish. A Pakistani number has somewhere else to go when
     * this fails; everyone else is told plainly to come back later.
     */
    fun checkGateway() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checkingGateway = true, error = null)
            val phone = _uiState.value.phoneNumber
            val canFallBack = phone.isNotBlank() && phoneNormalizer.isPakistaniMobile(phone)

            val health = waRepo.checkGateway()
            if (health.isFailure) {
                Log.w(TAG, "gateway down at verification time: ${health.exceptionOrNull()?.message}")
                _uiState.value = _uiState.value.copy(
                    checkingGateway = false,
                    gatewayUp = false,
                    smsFallbackOffered = canFallBack,
                )
                return@launch
            }

            waRepo.verifyTarget()
                .onSuccess { target ->
                    _uiState.value = _uiState.value.copy(
                        checkingGateway = false,
                        gatewayUp = true,
                        waNumber = target.phoneNumber,
                        waPhrase = target.phrase,
                        waLink = target.waLink,
                        smsFallbackOffered = canFallBack,
                    )
                }
                .onFailure {
                    Log.w(TAG, "verification target unavailable: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        checkingGateway = false,
                        gatewayUp = false,
                        smsFallbackOffered = canFallBack,
                    )
                }
        }
    }

    /** They have tapped through to WhatsApp; start watching for their message. */
    fun watchForOptIn() {
        val phone = _uiState.value.phoneNumber
        if (phone.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Save your number first")
            return
        }
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                watching = true,
                watchGaveUp = false,
                error = null,
                watchSecondsLeft = WATCH_SECONDS,
            )
            var waited = 0
            while (waited < WATCH_SECONDS) {
                val seen = waRepo.verifyOptIn(phone).getOrDefault(false)
                if (seen) {
                    Log.i(TAG, "opt-in message seen for the number being verified")
                    _uiState.value = _uiState.value.copy(
                        watching = false, optedIn = true, watchSecondsLeft = 0,
                    )
                    requestCode()
                    return@launch
                }
                delay(POLL_SECONDS * 1000L)
                waited += POLL_SECONDS
                _uiState.value = _uiState.value.copy(watchSecondsLeft = (WATCH_SECONDS - waited).coerceAtLeast(0))
            }
            // Every wait in this app has to end somewhere and say why.
            Log.i(TAG, "gave up watching for the opt-in message after $WATCH_SECONDS seconds")
            _uiState.value = _uiState.value.copy(
                watching = false, watchGaveUp = true, watchSecondsLeft = 0,
            )
        }
    }

    fun stopWatching() {
        watchJob?.cancel()
        _uiState.value = _uiState.value.copy(watching = false, watchSecondsLeft = 0)
    }

    /**
     * Asks the gateway for a code. It refuses when it has not seen the opt-in
     * message, and that refusal is shown as an instruction rather than a fault.
     */
    fun requestCode() {
        val phone = _uiState.value.phoneNumber
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            waRepo.verifySendCode(phone)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        codeSent = true,
                        optedIn = true,
                        toastMessage = "Code sent on WhatsApp",
                    )
                    throttle.markOtpSent(OtpChannel.PHONE)
                    startCooldownTicker()
                }
                .onFailure {
                    Log.w(TAG, "could not send the WhatsApp code: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = it.message ?: "Could not send the code",
                    )
                }
        }
    }

    /** Falls back to a code by text, which only a Pakistani number can receive. */
    fun useSmsFallback() {
        val uid = userRepo.currentFirebaseUser()?.uid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(error = "Not signed in — please log in again")
            return
        }
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = state.copy(isSending = true, error = null, usingSmsFallback = true)
            sendPhoneOtp(uid, state.phoneNumber, state.country)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        codeSent = true,
                        toastMessage = "Code sent by text",
                    )
                    startCooldownTicker()
                }
                .onFailure {
                    Log.w(TAG, "could not send the text code: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        usingSmsFallback = false,
                        error = it.message ?: "Failed to send code",
                    )
                }
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

    fun clearToast() { _uiState.value = _uiState.value.copy(toastMessage = null) }

    fun verify(code: String) {
        val uid = userRepo.currentFirebaseUser()?.uid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(error = "Not signed in — please log in again")
            return
        }
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val outcome = if (state.usingSmsFallback) {
                verifyPhoneOtp(uid, code)
            } else {
                waRepo.verifyCheckCode(state.phoneNumber, code.trim()).mapCatching { result ->
                    if (!result.verified) error(codeRefusalMessage(result))
                    verifyPhoneOtp.confirmVerified(uid).getOrThrow()
                }
            }
            outcome
                .onSuccess {
                    Log.i(TAG, "phone verified via ${if (state.usingSmsFallback) "text" else "WhatsApp"}")
                    _uiState.value = PhoneVerifyUiState(success = true, checkingGateway = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = it.message ?: "Verification failed",
                    )
                }
        }
    }

    private fun codeRefusalMessage(result: WhatsAppRepository.VerifyResult): String = when (result.reason) {
        "expired" -> "That code has expired. Ask for a new one."
        "too_many_attempts" -> "Too many wrong tries, so that code was cancelled. Ask for a new one."
        else -> {
            val left = result.attemptsRemaining
            if (left != null && left > 0) "That code is not right. $left tries left."
            else "That code is not right."
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    private companion object {
        // Long enough to switch apps, type and come back; short enough that a
        // person who wandered off gets told rather than left watching a spinner.
        const val WATCH_SECONDS = 120
        const val POLL_SECONDS = 3
    }
}
