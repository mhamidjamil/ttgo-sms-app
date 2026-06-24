package com.textgate.app.presentation.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.model.User
import com.textgate.app.domain.usecase.quota.CheckAndResetQuotaUseCase
import com.textgate.app.domain.usecase.quota.DecrementQuotaUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import com.textgate.app.domain.usecase.sms.EnqueueSmsUseCase
import com.textgate.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SendUiState(
    val user: User? = null,
    val effectiveQuota: Int = 0,
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
    val sentMessage: String? = null,
)

class SendViewModel(
    private val userRepo: UserRepository,
    private val checkAndResetQuota: CheckAndResetQuotaUseCase,
    private val getEffectiveQuota: GetEffectiveQuotaUseCase,
    private val decrementQuota: DecrementQuotaUseCase,
    private val enqueueSms: EnqueueSmsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SendUiState())
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    init { loadUser() }

    private fun loadUser() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val user = userRepo.getCurrentUser()
            if (user != null) {
                val refreshed = checkAndResetQuota(user).getOrDefault(user)
                val quota = getEffectiveQuota(refreshed)
                _uiState.value = SendUiState(user = refreshed, effectiveQuota = quota)
            } else {
                _uiState.value = SendUiState(error = "Could not load user data")
            }
        }
    }

    fun send(phone: String, message: String) {
        val user = _uiState.value.user ?: return
        val remaining = user.remainingQuota.coerceAtMost(_uiState.value.effectiveQuota)
        if (remaining <= 0) {
            _uiState.value = _uiState.value.copy(error = "Daily quota reached. Resets at midnight.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            enqueueSms(user.uid, phone, message)
                .onSuccess {
                    decrementQuota(user.uid)
                    val updated = user.copy(remainingQuota = (user.remainingQuota - 1).coerceAtLeast(0))
                    _uiState.value = SendUiState(
                        user = updated,
                        effectiveQuota = _uiState.value.effectiveQuota,
                        sentMessage = "SMS queued — the gateway will send it in a few seconds",
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = it.message ?: "Failed to queue SMS",
                    )
                }
        }
    }

    fun clearSentMessage() { _uiState.value = _uiState.value.copy(sentMessage = null) }
    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}
