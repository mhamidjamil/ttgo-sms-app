package com.spotwire.app.presentation.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.core.utils.Quota
import com.spotwire.app.domain.model.AutoHistoryEntry
import com.spotwire.app.domain.model.SmsStatus
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.usecase.auto.GetAutoHistoryUseCase
import com.spotwire.app.domain.usecase.auto.RefreshAutoJobStatusUseCase
import com.spotwire.app.domain.usecase.auto.RetryAutoArrivalUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Date

/**
 * One arrival, with every recipient that was messaged about it. Arriving at a
 * place with four contacts writes four rows, and showing four near identical
 * cards buried the one that failed.
 */
data class AutoArrivalGroup(
    val key: String,
    val location: String,
    val locationLabel: String,
    val sentAt: Date?,
    val routineTriggered: Boolean,
    val entries: List<AutoHistoryEntry>,
) {
    // Only the first line was written for this place; the sender signature and
    // the opt-out line are appended by the app. The place message field is
    // single-line, so this split is exact.
    val headline: String get() = entries.first().message.lineSequence().first().trim()
    val fullMessage: String get() = entries.first().message
}

data class AutoUiState(
    val groups: List<AutoArrivalGroup> = emptyList(),
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
                    _uiState.value = _uiState.value.copy(groups = group(entries), isLoading = false)
                }
                .launchIn(this)
        }
    }

    // A place plus a calendar day identifies exactly one arrival: recording an
    // arrival refuses to fire twice for the same place on the same day, so every
    // row sharing that pair is a recipient of the same message. The incoming
    // list is already newest first, so the groups come out in that order too.
    private fun group(entries: List<AutoHistoryEntry>): List<AutoArrivalGroup> =
        entries.groupBy { it.location to DateUtils.dayString(it.sentAt) }
            .map { (key, rows) ->
                val (location, day) = key
                AutoArrivalGroup(
                    key = "$location|$day",
                    location = location,
                    locationLabel = rows.firstNotNullOfOrNull { it.locationLabel.ifBlank { null } }.orEmpty(),
                    sentAt = rows.first().sentAt,
                    routineTriggered = rows.any { it.routineTriggered },
                    entries = rows,
                )
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

    // The card-level reload covers every recipient of that arrival at once, so
    // the user does not have to open the recipient list to unstick a group.
    fun refreshGroup(group: AutoArrivalGroup) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        val targets = group.entries.filter { it.channel == "sms" }
        viewModelScope.launch {
            targets.forEach { markBusy(it.id, true) }
            targets.forEach { entry ->
                refreshStatus(uid, entry).onFailure {
                    _uiState.value = _uiState.value.copy(error = it.message ?: "Could not refresh status")
                }
            }
            targets.forEach { markBusy(it.id, false) }
        }
    }

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
        _uiState.value.groups.flatMap { it.entries }
            .filter { it.channel == "sms" }
            .filter { it.status == SmsStatus.PENDING || it.status == SmsStatus.IN_PROGRESS }
            .forEach { entry ->
                // Every poll failure used to be dropped, so a page that could
                // not reach the gateway at all sat on stale statuses looking
                // healthy. Said once per visit: this runs on a timer, and one
                // message per tick would be noise.
                refreshStatus(uid, entry).onFailure {
                    if (_uiState.value.error == null) {
                        _uiState.value = _uiState.value.copy(
                            error = "Could not check the latest delivery status",
                        )
                    }
                }
            }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
