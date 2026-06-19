package com.spotwire.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.domain.model.SettingsChange
import com.spotwire.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class SettingsHistoryUiState(
    val changes: List<SettingsChange> = emptyList(),
    val isLoading: Boolean = true,
)

class SettingsHistoryViewModel(private val userRepo: UserRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsHistoryUiState())
    val uiState: StateFlow<SettingsHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val uid = userRepo.currentFirebaseUser()?.uid ?: return@launch
            userRepo.getSettingsHistory(uid)
                .onEach { _uiState.value = SettingsHistoryUiState(changes = it, isLoading = false) }
                .launchIn(this)
        }
    }
}
