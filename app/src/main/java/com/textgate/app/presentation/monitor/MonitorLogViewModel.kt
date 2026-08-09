package com.textgate.app.presentation.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.data.local.MonitorLogStore
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.PresenceState
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.services.ArrivalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaceStatus(val label: String, val state: PresenceState)

data class MonitorLogUiState(
    val isLoading: Boolean = true,
    val monitoringEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val lastObservedAt: Long = 0L,
    val placeStatuses: List<PlaceStatus> = emptyList(),
    val entries: List<MonitorLogStore.Entry> = emptyList(),
)

class MonitorLogViewModel(
    private val prefs: PreferencesDataSource,
    private val monitorLog: MonitorLogStore,
    private val userRepo: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonitorLogUiState())
    val uiState: StateFlow<MonitorLogUiState> = _uiState.asStateFlow()

    // Fetched once per screen visit: the page refreshes every few seconds and
    // the place list does not change underneath it, so re-reading it from the
    // server on every tick would be a pointless network cost.
    private var places: List<Place>? = null

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = places ?: userRepo.getCurrentUser()?.places
                ?.filter { it.savedBssids.isNotEmpty() }.orEmpty().also { places = it }
            _uiState.value = MonitorLogUiState(
                isLoading = false,
                monitoringEnabled = prefs.getMonitoringEnabled(),
                serviceRunning = ArrivalService.isRunning,
                lastObservedAt = prefs.getLastObservedAt(),
                placeStatuses = saved.map {
                    PlaceStatus(it.label.ifBlank { it.id }, prefs.getPresence(it.id).state)
                },
                entries = monitorLog.entries(),
            )
        }
    }
}
