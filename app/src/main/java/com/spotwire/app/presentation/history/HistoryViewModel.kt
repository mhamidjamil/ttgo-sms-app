package com.spotwire.app.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.domain.model.HistoryEntry
import com.spotwire.app.domain.model.SmsStatus
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.usecase.sms.GetHistoryUseCase
import com.spotwire.app.domain.usecase.sms.RefreshJobStatusUseCase
import com.spotwire.app.core.utils.Quota
import com.spotwire.app.data.local.PreferencesDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val refreshingIds: Set<String> = emptySet(),
    // Which half of the page opens first, from the user's own preference.
    val defaultTab: String = PreferencesDataSource.HISTORY_TAB_AUTOMATED,
)

class HistoryViewModel(
    private val userRepo: UserRepository,
    private val getHistory: GetHistoryUseCase,
    private val refreshStatus: RefreshJobStatusUseCase,
    private val prefs: PreferencesDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        loadHistory()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(defaultTab = prefs.getDefaultHistoryTab())
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val uid = userRepo.currentFirebaseUser()?.uid ?: return@launch
            getHistory(uid)
                .onEach { entries ->
                    _uiState.value = _uiState.value.copy(entries = entries, isLoading = false)
                }
                .launchIn(this)
        }
    }

    fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(Quota.HISTORY_POLL_SECONDS * 1000L)
                refreshPendingEntries()
            }
        }
    }

    fun stopPolling() { pollJob?.cancel(); pollJob = null }

    fun refreshEntry(entry: HistoryEntry) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                refreshingIds = _uiState.value.refreshingIds + entry.id
            )
            refreshStatus(uid, entry)
            _uiState.value = _uiState.value.copy(
                refreshingIds = _uiState.value.refreshingIds - entry.id
            )
        }
    }

    private fun refreshPendingEntries() {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        val pending = _uiState.value.entries.filter {
            it.status == SmsStatus.PENDING || it.status == SmsStatus.IN_PROGRESS
        }
        viewModelScope.launch {
            pending.forEach { entry -> refreshStatus(uid, entry) }
        }
    }
}
