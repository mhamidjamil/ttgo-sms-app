package com.textgate.app.presentation.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.core.utils.Quota
import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.model.SmsStatus
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.auto.GetAutoHistoryUseCase
import com.textgate.app.domain.usecase.auto.RefreshAutoJobStatusUseCase
import com.textgate.app.domain.usecase.auto.RetryAutoArrivalUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class AutoUiState(
    val entries: List<AutoHistoryEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
)

class AutoViewModel(
    private val userRepo: UserRepository,
    private val getAutoHistory: GetAutoHistoryUseCase,
    private val refreshStatus: RefreshAutoJobStatusUseCase,
    private val retryArrival: RetryAutoArrivalUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutoUiState())
    val uiState: StateFlow<AutoUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init { loadHistory() }

    private fun loadHistory() {
        viewModelScope.launch {
            val uid = userRepo.currentFirebaseUser()?.uid ?: return@launch
            getAutoHistory(uid)
                .onEach { entries ->
                    _uiState.value = _uiState.value.copy(entries = entries, isLoading = false)
                }
                .launchIn(this)
        }
    }

    // Same pattern as the History page: while the screen is visible, copy the
    // gateway job status into stale pending/in-progress entries. Without this
    // the Auto page showed "3 Jobs Pending" forever even after delivery.
    fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch {
            while (true) {
                refreshPendingEntries()
                delay(Quota.HISTORY_POLL_SECONDS * 1000L)
            }
        }
    }

    fun stopPolling() { pollJob?.cancel(); pollJob = null }

    // Manual reload of one entry, matching the refresh button on manual history.
    fun refreshEntry(entry: AutoHistoryEntry) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch {
            markBusy(entry.id, true)
            refreshStatus(uid, entry).onFailure {
                _uiState.value = _uiState.value.copy(error = it.message ?: "Could not refresh status")
            }
            markBusy(entry.id, false)
        }
    }

    fun retryEntry(entry: AutoHistoryEntry) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch {
            markBusy(entry.id, true)
            retryArrival(uid, entry).onFailure {
                _uiState.value = _uiState.value.copy(error = it.message ?: "Could not retry")
            }
            markBusy(entry.id, false)
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    private fun markBusy(id: String, busy: Boolean) {
        val ids = _uiState.value.busyIds
        _uiState.value = _uiState.value.copy(busyIds = if (busy) ids + id else ids - id)
    }

    private suspend fun refreshPendingEntries() {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        _uiState.value.entries
            .filter { it.channel == "sms" }
            .filter { it.status == SmsStatus.PENDING || it.status == SmsStatus.IN_PROGRESS }
            .forEach { refreshStatus(uid, it) }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
