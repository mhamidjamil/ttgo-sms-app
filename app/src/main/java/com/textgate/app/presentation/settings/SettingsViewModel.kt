package com.textgate.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.SettingsChange
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
        val places = _uiState.value.places.map { if (it.id == updated.id) updated else it }
        _uiState.value = _uiState.value.copy(places = places)
        persistPlaces(places)
    }

    // A brand new place has nothing worth writing yet; it persists on first edit.
    fun addPlace() {
        val newPlace = Place(id = "place_${System.currentTimeMillis()}", label = "")
        _uiState.value = _uiState.value.copy(places = _uiState.value.places + newPlace)
    }

    // home/office are permanent; only user-added places can be removed.
    fun removePlace(id: String) {
        if (Place.isDefaultId(id)) return
        val places = _uiState.value.places.filterNot { it.id == id }
        _uiState.value = _uiState.value.copy(places = places)
        persistPlaces(places)
    }

    fun save(guardianNumber: String) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        val places = _uiState.value.places.filter(::isWorthSaving)
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

    // Place edits write straight through instead of waiting for the Save button
    // at the bottom of the screen, which is what lost added contacts. The
    // guardian number is left alone here because its text field may be mid-edit.
    private fun persistPlaces(places: List<Place>) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveSuccess = false)
            savePlaces.savePlacesOnly(uid, places.filter(::isWorthSaving))
                .onSuccess { _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = it.message ?: "Failed to save place",
                    )
                }
        }
    }

    // Custom places that are still completely blank are dropped. Having contacts
    // counts as content — the old check looked only at label and BSSID, so a
    // place someone had only added contacts to was thrown away on save.
    private fun isWorthSaving(place: Place) =
        Place.isDefaultId(place.id) || place.label.isNotBlank() ||
            place.bssid.isNotBlank() || place.contacts.isNotEmpty()

    fun clearSuccess() { _uiState.value = _uiState.value.copy(saveSuccess = false) }

    suspend fun getMonitoringEnabled(): Boolean = prefs.getMonitoringEnabled()

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        val was = prefs.getMonitoringEnabled()
        prefs.setMonitoringEnabled(enabled)
        if (was == enabled) return
        // Audited so a monitoring switch that flips on its own can be traced.
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        userRepo.logSettingsChanges(uid, listOf(SettingsChange(
            field = "Arrival monitoring",
            oldValue = if (was) "on" else "off",
            newValue = if (enabled) "on" else "off",
        )))
    }
}
