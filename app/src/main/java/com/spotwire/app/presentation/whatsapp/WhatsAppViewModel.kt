package com.spotwire.app.presentation.whatsapp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.repository.WhatsAppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WhatsAppVM"

data class WhatsAppUiState(
    val isLoading: Boolean = true,
    // A gateway key of their own is connected.
    val provisioned: Boolean = false,
    val ownStatus: String? = null,          // connecting | qr_ready | connected | disconnected
    val qrBase64: String? = null,           // data-URL PNG while linking
    val isLinking: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val info: String? = null,

    // ── Own gateway key (portal) ──────────────────────────────────────────────
    val ownKeyPhone: String? = null,
    val portalUrl: String = "",
    // The paste form is open. Opens by default when there is nothing set up.
    val showKeyForm: Boolean = false,
    val keyId: String = "",
    val keySecret: String = "",
    val keyError: String? = null,
    val isSavingKey: Boolean = false,

    // ── Gateway health ────────────────────────────────────────────────────────
    // Answered by the one endpoint that needs no credential, so "is the service
    // even up" is separable from "is my key any good".
    val isCheckingGateway: Boolean = false,
    val gatewayUp: Boolean? = null,             // null = never checked in this session
    val gatewayWhatsAppConnected: Boolean? = null,
    val gatewayCheckedLabel: String? = null,
)

class WhatsAppViewModel(
    private val waRepo: WhatsAppRepository,
    private val userRepo: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WhatsAppUiState())
    val uiState: StateFlow<WhatsAppUiState> = _uiState.asStateFlow()

    private var linkJob: Job? = null

    init { setup() }

    /** What is connected, and is the service that would carry it even up? */
    fun setup() {
        viewModelScope.launch {
            _uiState.value = WhatsAppUiState(isLoading = true)
            val portal = waRepo.portalUrl()
            val existing = waRepo.getLinkInfo()
            _uiState.value = WhatsAppUiState(
                isLoading = false,
                provisioned = existing != null,
                ownKeyPhone = existing?.phoneNumber,
                portalUrl = portal,
                // Nothing is connected, so the one thing to do is connect it.
                showKeyForm = existing == null,
            )
            if (existing != null) refreshStatuses()
            checkGateway()
        }
    }

    fun refreshStatuses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val own = waRepo.getStatus().getOrNull()
            _uiState.value = _uiState.value.copy(isBusy = false, ownStatus = own)
        }
    }

    /**
     * "Is the gateway down, or is it me?" A message that will not send has two
     * very different causes and until now the app could not tell them apart, so
     * every failure looked like a broken key.
     */
    fun checkGateway() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingGateway = true)
            val stamp = DateUtils.currentTime12h()
            waRepo.checkGateway()
                .onSuccess { health ->
                    Log.i(TAG, "gateway health ok, whatsapp=${health.whatsAppConnected}")
                    _uiState.value = _uiState.value.copy(
                        isCheckingGateway = false,
                        gatewayUp = true,
                        gatewayWhatsAppConnected = health.whatsAppConnected,
                        gatewayCheckedLabel = "Checked at $stamp",
                    )
                }
                .onFailure {
                    Log.w(TAG, "gateway health failed: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        isCheckingGateway = false,
                        gatewayUp = false,
                        gatewayWhatsAppConnected = null,
                        gatewayCheckedLabel = "Checked at $stamp",
                    )
                }
        }
    }

    // ── Own gateway key ───────────────────────────────────────────────────────

    fun setKeyId(value: String) { _uiState.value = _uiState.value.copy(keyId = value, keyError = null) }

    fun setKeySecret(value: String) { _uiState.value = _uiState.value.copy(keySecret = value, keyError = null) }

    fun toggleKeyForm() {
        _uiState.value = _uiState.value.copy(
            showKeyForm = !_uiState.value.showKeyForm, keyError = null,
        )
    }

    /**
     * Checks the pasted key against the gateway and stores it only once the
     * gateway has confirmed it and named the number it sends from. Nothing is
     * saved on failure, so a typo cannot leave the app configured but broken.
     */
    fun saveOwnKey() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isSavingKey = true, keyError = null, info = null)
            waRepo.saveOwnKey(state.keyId, state.keySecret)
                .onSuccess { link ->
                    _uiState.value = _uiState.value.copy(
                        isSavingKey = false,
                        provisioned = true,
                        showKeyForm = false,
                        keyId = "",
                        keySecret = "",
                        ownKeyPhone = link.phoneNumber,
                        info = link.phoneNumber
                            ?.let { "Connected. Messages will come from $it." }
                            ?: "Connected to your own WhatsApp gateway.",
                    )
                    refreshStatuses()
                }
                .onFailure {
                    Log.w(TAG, "gateway key rejected: ${it.message}")
                    _uiState.value = _uiState.value.copy(
                        isSavingKey = false,
                        keyError = it.message ?: "Could not verify that key",
                    )
                }
        }
    }

    /** Forgets the stored key so a different one can be pasted. */
    fun disconnectOwnKey() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            waRepo.clearLink()
            setup()
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
            waRepo.sendMessage(ownPhone, "Spotwire WhatsApp test — your setup works!", user?.name)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        info = "Test queued — it arrives within ~15 s",
                    )
                }
                .onFailure {
                    Log.w(TAG, "test send failed: ${it.message}")
                    _uiState.value = _uiState.value.copy(isBusy = false, error = it.message)
                }
        }
    }

    fun clearMessages() { _uiState.value = _uiState.value.copy(error = null, info = null) }

    override fun onCleared() {
        stopLinking()
        super.onCleared()
    }
}
