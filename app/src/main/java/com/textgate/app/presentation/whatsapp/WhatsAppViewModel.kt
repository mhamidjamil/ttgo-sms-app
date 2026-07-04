package com.textgate.app.presentation.whatsapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import com.textgate.app.domain.repository.WhatsAppRepository.Companion.MODE_OWN
import com.textgate.app.domain.repository.WhatsAppRepository.Companion.MODE_SHARED
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WhatsAppUiState(
    val isLoading: Boolean = true,
    // Both phone + email verified — SSO eligibility.
    val eligible: Boolean = true,
    // Gateway account exists (auto-provisioned).
    val provisioned: Boolean = false,
    // Provisioning/availability failure (e.g. the maintenance message).
    val setupError: String? = null,
    val mode: String = MODE_SHARED,
    val sharedConnected: Boolean? = null,   // null = unknown yet
    val ownStatus: String? = null,          // connecting | qr_ready | connected | disconnected
    val qrBase64: String? = null,           // data-URL PNG while linking
    val isLinking: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

class WhatsAppViewModel(
    private val waRepo: WhatsAppRepository,
    private val userRepo: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WhatsAppUiState())
    val uiState: StateFlow<WhatsAppUiState> = _uiState.asStateFlow()

    private var linkJob: Job? = null

    init { setup() }

    /** Full setup pass: eligibility → auto-provision → mode + statuses. */
    fun setup() {
        viewModelScope.launch {
            _uiState.value = WhatsAppUiState(isLoading = true)
            val user = userRepo.getCurrentUser()
            val eligible = user != null && user.phoneVerified && user.emailVerified
            if (!eligible) {
                _uiState.value = WhatsAppUiState(isLoading = false, eligible = false)
                return@launch
            }
            waRepo.ensureProvisioned()
                .onSuccess { provisioned ->
                    _uiState.value = WhatsAppUiState(
                        isLoading = false,
                        eligible = true,
                        provisioned = provisioned,
                        mode = waRepo.getMode(),
                    )
                    if (provisioned) refreshStatuses()
                }
                .onFailure {
                    // Health gate / provisioning failed — e.g. "under maintenance".
                    _uiState.value = WhatsAppUiState(
                        isLoading = false,
                        eligible = true,
                        provisioned = false,
                        setupError = it.message ?: "WhatsApp setup failed — try again later",
                    )
                }
        }
    }

    fun refreshStatuses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val shared = waRepo.getSharedConnected().getOrNull()
            val own = waRepo.getStatus().getOrNull()
            _uiState.value = _uiState.value.copy(
                isBusy = false,
                sharedConnected = shared,
                ownStatus = own,
            )
        }
    }

    fun selectShared() {
        viewModelScope.launch {
            stopLinking()
            waRepo.setMode(MODE_SHARED)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        mode = MODE_SHARED, qrBase64 = null,
                        info = "Messages will be sent from the shared TextGate number",
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun selectOwn() {
        viewModelScope.launch {
            waRepo.setMode(MODE_OWN)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(mode = MODE_OWN)
                    // Not linked yet → start the QR flow right away.
                    if (_uiState.value.ownStatus != "connected") startLinking()
                }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    /**
     * "Use My WhatsApp" QR flow: start the session (id is the verified phone —
     * generated automatically, never typed), then poll QR + status until the
     * user scans and the socket connects. Cancels itself after ~5 minutes.
     */
    fun startLinking() {
        stopLinking()
        linkJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLinking = true, error = null, qrBase64 = null)
            waRepo.startOwnLinking().onFailure {
                _uiState.value = _uiState.value.copy(isLinking = false, error = it.message)
                return@launch
            }
            repeat(120) {
                delay(2_500)
                val status = waRepo.getStatus().getOrNull()
                if (status == "connected") {
                    _uiState.value = _uiState.value.copy(
                        isLinking = false, qrBase64 = null, ownStatus = "connected",
                        info = "WhatsApp linked — messages now come from your own number!",
                    )
                    return@launch
                }
                val qr = waRepo.getQr().getOrNull()
                _uiState.value = _uiState.value.copy(
                    ownStatus = status ?: _uiState.value.ownStatus,
                    qrBase64 = qr ?: _uiState.value.qrBase64,
                )
            }
            _uiState.value = _uiState.value.copy(
                isLinking = false, qrBase64 = null,
                error = "Linking timed out — tap Link again to get a fresh QR",
            )
        }
    }

    private fun stopLinking() {
        linkJob?.cancel()
        linkJob = null
        if (_uiState.value.isLinking) {
            _uiState.value = _uiState.value.copy(isLinking = false, qrBase64 = null)
        }
    }

    // Sends a test message (via the active mode) to the user's own number.
    fun sendTest() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, error = null, info = null)
            val user = userRepo.getCurrentUser()
            val ownPhone = user?.phoneNumber.orEmpty()
            if (ownPhone.isBlank()) {
                _uiState.value = _uiState.value.copy(isBusy = false, error = "Verify your phone number first")
                return@launch
            }
            waRepo.sendMessage(ownPhone, "TextGate WhatsApp test — your setup works!", user?.name)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        info = "Test queued — it arrives within ~15 s",
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(isBusy = false, error = it.message) }
        }
    }

    fun clearMessages() { _uiState.value = _uiState.value.copy(error = null, info = null) }

    override fun onCleared() {
        stopLinking()
        super.onCleared()
    }
}
