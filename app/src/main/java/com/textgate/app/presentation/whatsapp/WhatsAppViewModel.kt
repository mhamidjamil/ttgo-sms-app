package com.textgate.app.presentation.whatsapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WhatsAppUiState(
    val isLinked: Boolean = false,
    val savedSessionId: String = "",
    val status: String? = null,       // connecting | qr_ready | connected | disconnected
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

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val link = waRepo.getLink()
            _uiState.value = _uiState.value.copy(
                isLinked = link != null,
                savedSessionId = link?.second ?: "",
            )
            if (link != null) checkStatus()
        }
    }

    fun saveLink(apiKey: String, sessionId: String) {
        if (apiKey.isBlank() || sessionId.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter both the API key and the session name")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, error = null, info = null)
            waRepo.saveLink(apiKey, sessionId)
            _uiState.value = _uiState.value.copy(
                isBusy = false, isLinked = true, savedSessionId = sessionId.trim(),
                info = "Saved — checking session…",
            )
            checkStatus()
        }
    }

    fun checkStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, error = null)
            waRepo.getStatus()
                .onSuccess { _uiState.value = _uiState.value.copy(isBusy = false, status = it, info = null) }
                .onFailure { _uiState.value = _uiState.value.copy(isBusy = false, status = null, error = it.message) }
        }
    }

    // Sends a test WhatsApp message to the user's own verified phone number.
    fun sendTest() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, error = null, info = null)
            val user = userRepo.getCurrentUser()
            val ownPhone = user?.phoneNumber.orEmpty()
            if (ownPhone.isBlank()) {
                _uiState.value = _uiState.value.copy(isBusy = false, error = "Verify your phone number first — the test goes to your own number")
                return@launch
            }
            waRepo.sendMessage(ownPhone, "TextGate WhatsApp test — your gateway link works!", user?.name)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        info = "Test queued — it arrives within ~15 s (the gateway paces sends)",
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(isBusy = false, error = it.message) }
        }
    }

    fun unlink() {
        viewModelScope.launch {
            waRepo.clearLink()
            _uiState.value = WhatsAppUiState(info = "WhatsApp unlinked")
        }
    }

    fun clearMessages() { _uiState.value = _uiState.value.copy(error = null, info = null) }
}
