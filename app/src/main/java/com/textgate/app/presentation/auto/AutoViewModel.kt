package com.textgate.app.presentation.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.auto.GetAutoHistoryUseCase
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutoUiState())
    val uiState: StateFlow<AutoUiState> = _uiState.asStateFlow()

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
}
