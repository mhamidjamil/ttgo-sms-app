package com.spotwire.app.presentation.whatsapp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.repository.WhatsAppRepository
import com.spotwire.app.domain.repository.WhatsAppRepository.Companion.MODE_OWN
import com.spotwire.app.domain.repository.WhatsAppRepository.Companion.MODE_SHARED
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WhatsAppVM"

data class WhatsAppUiState(
    val isLoading: Boolean = true,
    // Both phone + email verified — SSO eligibility.
    val eligible: Boolean = true,
    // Gateway account exists (auto-provisioned, or a key the user pasted).
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

    // ── Own gateway key (portal) ──────────────────────────────────────────────
    // The link came from a key the user created on the gateway portal. Such a
    // key sends only from their own number, so the mode choice does not apply.
    val ownKey: Boolean = false,
    val ownKeyPhone: String? = null,
    val portalUrl: String = "",
    // The paste form is open. Opens by default when there is nothing set up.
    val showKeyForm: Boolean = false,
    val keyId: String = "",
    val keySecret: String = "",
    val keyError: String? = null,
    val isSavingKey: Boolean = false,
)

class WhatsAppViewModel(
    private val waRepo: WhatsAppRepository,
    private val userRepo: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WhatsAppUiState())
    val uiState: StateFlow<WhatsAppUiState> = _uiState.asStateFlow()

    private var linkJob: Job? = null

    init { setup() }

    /** Full setup pass: eligibility → existing link or SSO → mode + statuses. */
    fun setup() {
        viewModelScope.launch {
            _uiState.value = WhatsAppUiState(isLoading = true)
            val portal = waRepo.portalUrl()
            val user = userRepo.getCurrentUser()
            val eligible = user != null && user.phoneVerified && user.emailVerified

            // A key the user pasted stands on its own: it does not need the SSO
            // path, and it does not need email verification either.
            val existing = waRepo.getLinkInfo()
            if (existing != null) {
                _uiState.value = WhatsAppUiState(
                    isLoading = false,
                    eligible = eligible,
                    provisioned = true,
                    mode = waRepo.getMode(),
                    ownKey = existing.ownKey,
                    ownKeyPhone = existing.phoneNumber,
                    portalUrl = portal,
                )
                refreshStatuses()
                return@launch
            }

            if (!eligible) {
                _uiState.value = WhatsAppUiState(
                    isLoading = false, eligible = false, portalUrl = portal, showKeyForm = true,
                )
                return@launch
            }

            waRepo.ensureProvisioned()
                .onSuccess { provisioned ->
                    _uiState.value = WhatsAppUiState(
                        isLoading = false,
                        eligible = true,
                        provisioned = provisioned,
                        mode = waRepo.getMode(),
                        portalUrl = portal,
                        showKeyForm = !provisioned,
                    )
                    if (provisioned) refreshStatuses()
                }
                .onFailure {
                    // The gateway declined — most often because it has SSO
                    // switched off. Its own words are kept, and the manual
                    // portal key is offered underneath as the way through.
                    Log.i(TAG, "gateway setup unavailable: ${it.message}")
                    _uiState.value = WhatsAppUiState(
                        isLoading = false,
                        eligible = true,
                        provisioned = false,
                        setupError = it.message ?: "WhatsApp setup failed — try again later",
                        portalUrl = portal,
                        showKeyForm = true,
                    )
                }
        }
    }

    fun refreshStatuses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            // A portal key cannot send through the shared number, so asking
            // about it would only show a status the user can never act on.
            val shared = if (_uiState.value.ownKey) null else waRepo.getSharedConnected().getOrNull()
            val own = waRepo.getStatus().getOrNull()
            _uiState.value = _uiState.value.copy(
                isBusy = false,
                sharedConnected = shared,
                ownStatus = own,
            )
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
                        setupError = null,
                        showKeyForm = false,
                        keyId = "",
                        keySecret = "",
                        ownKey = true,
                        ownKeyPhone = link.phoneNumber,
                        mode = MODE_OWN,
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

    fun selectShared() {
        viewModelScope.launch {
            stopLinking()
            waRepo.setMode(MODE_SHARED)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        mode = MODE_SHARED, qrBase64 = null,
                        info = "Messages will be sent from the shared Spotwire number",
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
