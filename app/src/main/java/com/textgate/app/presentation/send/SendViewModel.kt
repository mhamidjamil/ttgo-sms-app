package com.textgate.app.presentation.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.quota.CheckAndResetQuotaUseCase
import com.textgate.app.domain.usecase.quota.DecrementQuotaUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import com.textgate.app.domain.repository.ThrottleRepository
import com.textgate.app.domain.usecase.quota.RequestMoreSmsUseCase
import com.textgate.app.domain.usecase.sms.EnqueueSmsUseCase
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
    val isRequestingMore: Boolean = false,
    val requestMoreResult: String? = null,
    // False once today's single quota-increase request has been sent.
    val canRequestMoreToday: Boolean = true,
) {
    // How many SMS were sent today = assigned minus what's left.
    // Comparing sentToday against effectiveQuota correctly caps unverified users:
    // e.g. assigned=10, remaining=8 → sentToday=2; if effectiveQuota=2 → blocked.
    val sentToday: Int get() = user
        ?.let { (it.assignedQuota - it.remainingQuota).coerceAtLeast(0) } ?: 0
    val remainingToday: Int get() = (effectiveQuota - sentToday).coerceAtLeast(0)
    val canSendMore: Boolean get() = sentToday < effectiveQuota
}

class SendViewModel(
    private val userRepo: UserRepository,
    private val checkAndResetQuota: CheckAndResetQuotaUseCase,
    private val getEffectiveQuota: GetEffectiveQuotaUseCase,
    private val decrementQuota: DecrementQuotaUseCase,
    private val enqueueSms: EnqueueSmsUseCase,
    private val requestMoreSms: RequestMoreSmsUseCase,
    private val throttle: ThrottleRepository,
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
                val canRequest = throttle.canRequestMoreSmsToday()
                _uiState.value = SendUiState(
                    user = refreshed,
                    effectiveQuota = quota,
                    canRequestMoreToday = canRequest,
                )
            } else {
                _uiState.value = SendUiState(error = "Could not load user data")
            }
        }
    }

    fun send(phone: String, message: String) {
        val user = _uiState.value.user ?: return
        if (!_uiState.value.canSendMore) {
            _uiState.value = _uiState.value.copy(error = "Daily quota reached. Resets at midnight.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            enqueueSms(user.uid, phone, message, senderPhone = user.phoneNumber)
                .onSuccess {
                    decrementQuota(user.uid)
                    val updated = user.copy(remainingQuota = (user.remainingQuota - 1).coerceAtLeast(0))
                    _uiState.value = SendUiState(
                        user = updated,
                        effectiveQuota = _uiState.value.effectiveQuota,
                        sentMessage = "Message queued successfully.",
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

    // "Request more SMS" — emails the admin with the user's identity plus their
    // own note so the daily quota can be raised. Limited to one per day.
    fun requestMore(note: String) {
        val user = _uiState.value.user ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRequestingMore = true, requestMoreResult = null)
            requestMoreSms(user, note)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isRequestingMore = false,
                        canRequestMoreToday = false,
                        requestMoreResult = "Request sent — the admin will review it soon.",
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isRequestingMore = false,
                        // A "once per day" rejection also means the button should lock.
                        canRequestMoreToday = throttleAllowsAfterFailure(it),
                        requestMoreResult = it.message ?: "Failed to send the request",
                    )
                }
        }
    }

    // Only an already-sent-today failure should lock the button; a transient
    // send error (e.g. no network) leaves the user free to retry.
    private fun throttleAllowsAfterFailure(t: Throwable): Boolean =
        t.message?.contains("already sent a request today") != true

    fun clearSentMessage() { _uiState.value = _uiState.value.copy(sentMessage = null) }
    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
    fun clearRequestMoreResult() { _uiState.value = _uiState.value.copy(requestMoreResult = null) }
}
