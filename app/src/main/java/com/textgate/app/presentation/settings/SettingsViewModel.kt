package com.textgate.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.location.SaveLocationSettingsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val guardianNumber: String = "",
    val homeBssid: String = "",
    val homeLabel: String = "",
    val officeBssid: String = "",
    val officeLabel: String = "",
    val error: String? = null,
    val saveSuccess: Boolean = false,
)

class SettingsViewModel(
    private val userRepo: UserRepository,
    private val saveLocationSettings: SaveLocationSettingsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init { loadUser() }

    private fun loadUser() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            if (user != null) {
                _uiState.value = SettingsUiState(
                    isLoading = false,
                    guardianNumber = user.guardianNumber,
                    homeBssid = user.homeBssid,
                    homeLabel = user.homeLabel,
                    officeBssid = user.officeBssid,
                    officeLabel = user.officeLabel,
                )
            } else {
                _uiState.value = SettingsUiState(isLoading = false, error = "Could not load settings")
            }
        }
    }

    fun saveSettings(
        guardianNumber: String,
        homeBssid: String,
        homeLabel: String,
        officeBssid: String,
        officeLabel: String,
    ) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveSuccess = false)
            saveLocationSettings(uid, guardianNumber, homeBssid, homeLabel, officeBssid, officeLabel)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = it.message ?: "Failed to save settings",
                    )
                }
        }
    }

    fun clearSuccess() { _uiState.value = _uiState.value.copy(saveSuccess = false) }
}
