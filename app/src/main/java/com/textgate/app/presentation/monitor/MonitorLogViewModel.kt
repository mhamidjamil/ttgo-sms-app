package com.textgate.app.presentation.monitor

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.withContext

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
            // Only a read that actually returned an account is worth keeping. A
            // single failed read used to be remembered as "this phone has no
            // places", and the page then hid them until it was left and reopened.
            val saved = places ?: userRepo.getCurrentUser()?.places
                ?.filter { it.savedBssids.isNotEmpty() }?.also { places = it }.orEmpty()
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

    /**
     * Writes the log out and hands it to the share sheet, so the phone's own
     * account of what monitoring did can be sent for diagnosis instead of
     * described from memory.
     */
    fun exportLog(context: Context) {
        viewModelScope.launch {
            val shared = withContext(Dispatchers.IO) {
                runCatching {
                    FileProvider.getUriForFile(
                        context, "com.textgate.app.fileprovider", monitorLog.exportFile(context))
                }
            }
            shared.onSuccess { uri ->
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Monitoring log",
                ))
            }.onFailure {
                // A share sheet that never opens looks like a dead button, so the
                // failure is said out loud and left in the log to be found later.
                Log.w("TextGateMonitorLog", "Could not write the log export", it)
                Toast.makeText(context, "Could not write the log file", Toast.LENGTH_LONG).show()
            }
        }
    }
}
