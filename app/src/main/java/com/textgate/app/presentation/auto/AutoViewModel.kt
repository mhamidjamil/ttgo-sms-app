package com.textgate.app.presentation.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.core.utils.Quota
import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.model.SmsStatus
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.auto.GetAutoHistoryUseCase
import com.textgate.app.domain.usecase.auto.RefreshAutoJobStatusUseCase
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
)

class AutoViewModel(
    private val userRepo: UserRepository,
    private val getAutoHistory: GetAutoHistoryUseCase,
    private val refreshStatus: RefreshAutoJobStatusUseCase,
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
