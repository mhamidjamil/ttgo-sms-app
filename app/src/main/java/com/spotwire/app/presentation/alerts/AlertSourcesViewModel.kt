package com.spotwire.app.presentation.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.domain.model.AlertSubscription
import com.spotwire.app.domain.model.IncomingAlert
import com.spotwire.app.domain.repository.AlertRepository
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.usecase.alerts.UnsubscribeFromSenderUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class AlertSourcesUiState(
    val subscriptions: List<AlertSubscription> = emptyList(),
    // The alerts actually delivered to this person inside the app. Only what was
    // sent to them: never the sender's own history, which is theirs alone.
    val alerts: List<IncomingAlert> = emptyList(),
    val isLoading: Boolean = true,
    // Blank when the phone is not verified yet — nothing can be matched without it.
    val myPhone: String = "",
    val error: String? = null,
    val busyUids: Set<String> = emptySet(),
)

class AlertSourcesViewModel(
    private val userRepo: UserRepository,
    private val alertRepo: AlertRepository,
    private val unsubscribe: UnsubscribeFromSenderUseCase,
    private val linkRepo: LinkRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertSourcesUiState())
    val uiState: StateFlow<AlertSourcesUiState> = _uiState.asStateFlow()

    private var myName: String = ""
    private var myUid: String = ""

    init { load() }

    // Relationships are keyed by the verified number, so simply verifying the
    // same number surfaces every sender who has ever alerted it — even if they
    // started months before this account existed.
    private fun load() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            if (user == null || !user.phoneVerified || user.phoneNumber.isBlank()) {
                _uiState.value = AlertSourcesUiState(
                    isLoading = false,
                    error = "Verify your phone number to see who is sending you alerts",
                )
                return@launch
            }
            myName = user.name
            myUid = user.uid
            _uiState.value = _uiState.value.copy(myPhone = user.phoneNumber)
            linkRepo.incomingAlerts(user.uid)
                .onEach { alerts -> _uiState.value = _uiState.value.copy(alerts = alerts) }
                .launchIn(this)
            alertRepo.getSubscriptions(user.phoneNumber)
                .onEach { subs ->
                    _uiState.value = _uiState.value.copy(
                        subscriptions = subs.sortedByDescending { it.lastAlertAt },
                        isLoading = false,
                    )
                }
                .launchIn(this)
        }
    }

    fun setSubscribed(subscription: AlertSubscription, subscribed: Boolean) {
        val phone = _uiState.value.myPhone
        if (phone.isBlank()) return
        viewModelScope.launch {
            markBusy(subscription.senderUid, true)
            val result = if (subscribed) {
                alertRepo.setSubscribed(phone, subscription.senderUid, true)
            } else {
                unsubscribe(phone, myName, myUid, subscription)
            }
            result.onFailure {
                _uiState.value = _uiState.value.copy(error = it.message ?: "Could not update")
            }
            markBusy(subscription.senderUid, false)
        }
    }

    /** Opening the list is reading it, so nothing stays unread behind their back. */
    fun markAlertsSeen() {
        val uid = myUid.ifBlank { return }
        val unseen = _uiState.value.alerts.filter { !it.seen }
        if (unseen.isEmpty()) return
        viewModelScope.launch { unseen.forEach { linkRepo.markAlertSeen(uid, it.id) } }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    private fun markBusy(uid: String, busy: Boolean) {
        val ids = _uiState.value.busyUids
        _uiState.value = _uiState.value.copy(busyUids = if (busy) ids + uid else ids - uid)
    }
}
