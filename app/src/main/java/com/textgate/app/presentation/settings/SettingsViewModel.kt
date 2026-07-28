package com.textgate.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import com.textgate.app.domain.usecase.location.SavePlacesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    // Fallback recipient for places that have no contacts of their own.
    val guardianNumber: String = "",
    val places: List<Place> = emptyList(),
    val error: String? = null,
    val saveSuccess: Boolean = false,
    // WhatsApp gateway set up → the place editor offers a WhatsApp message field.
    val waConfigured: Boolean = false,
)

class SettingsViewModel(
    private val userRepo: UserRepository,
    private val savePlaces: SavePlacesUseCase,
    private val waRepo: WhatsAppRepository,
    private val prefs: PreferencesDataSource,
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
                    // toDomain() migrates legacy home/office fields into the
                    // places list, so old accounts land here with both seeded.
                    places = user.places.ifEmpty { Place.defaults() },
                    waConfigured = waRepo.isLinked(),
                )
            } else {
                _uiState.value = SettingsUiState(isLoading = false, error = "Could not load settings")
            }
        }
    }

    // Whole-place update — the place editor dialog applies its draft through
    // this (label, message, contacts) and the WiFi picker updates bssid/label.
    fun updatePlace(updated: Place) {
        _uiState.value = _uiState.value.copy(
            places = _uiState.value.places.map { if (it.id == updated.id) updated else it },
        )
    }

    fun addPlace() {
        val newPlace = Place(id = "place_${System.currentTimeMillis()}", label = "")
        _uiState.value = _uiState.value.copy(places = _uiState.value.places + newPlace)
    }

    // home/office are permanent; only user-added places can be removed.
    fun removePlace(id: String) {
        if (Place.isDefaultId(id)) return
        _uiState.value = _uiState.value.copy(
            places = _uiState.value.places.filterNot { it.id == id },
        )
    }

    fun save(guardianNumber: String) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        // Drop custom places that are still completely empty (no label, no BSSID).
        val places = _uiState.value.places.filter {
            Place.isDefaultId(it.id) || it.label.isNotBlank() || it.bssid.isNotBlank()
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveSuccess = false)
            savePlaces(uid, guardianNumber, places)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false, saveSuccess = true,
                        guardianNumber = guardianNumber, places = places,
                    )
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

    suspend fun getMonitoringEnabled(): Boolean = prefs.getMonitoringEnabled()

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        prefs.setMonitoringEnabled(enabled)
    }
}
