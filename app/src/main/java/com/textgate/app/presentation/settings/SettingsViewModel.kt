package com.textgate.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.PlaceContact
import com.textgate.app.domain.model.SettingsChange
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import com.textgate.app.domain.usecase.location.GetPlaceRecipientsUseCase
import com.textgate.app.domain.usecase.location.SavePlacesUseCase
import com.textgate.app.domain.usecase.location.SendLocationNowUseCase
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

    // "Send my location now" prompt — non-null place id means it is open.
    val locationPlaceId: String? = null,
    val locationRecipients: List<PlaceContact> = emptyList(),
    val isLoadingRecipients: Boolean = false,
    val isSendingLocation: Boolean = false,
    val locationResult: String? = null,
)

class SettingsViewModel(
    private val userRepo: UserRepository,
    private val savePlaces: SavePlacesUseCase,
    private val waRepo: WhatsAppRepository,
    private val prefs: PreferencesDataSource,
    private val getPlaceRecipients: GetPlaceRecipientsUseCase,
    private val sendLocationNow: SendLocationNowUseCase,
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

    // The prompt opens straight away and fills in as the recipients load, so the
    // user is not left looking at a frozen button while links are read.
    fun openLocationPrompt(placeId: String) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        _uiState.value = _uiState.value.copy(
            locationPlaceId = placeId,
            locationRecipients = emptyList(),
            isLoadingRecipients = true,
        )
        viewModelScope.launch {
            val recipients = getPlaceRecipients(uid, placeId)
            _uiState.value = _uiState.value.copy(
                locationRecipients = recipients,
                isLoadingRecipients = false,
            )
        }
    }

    fun dismissLocationPrompt() {
        _uiState.value = _uiState.value.copy(locationPlaceId = null, locationRecipients = emptyList())
    }

    fun sendCurrentLocation(placeId: String, recipients: List<PlaceContact>) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingLocation = true)
            sendLocationNow(uid, placeId, recipients)
                .onSuccess { queued ->
                    _uiState.value = _uiState.value.copy(
                        isSendingLocation = false,
                        locationPlaceId = null,
                        locationRecipients = emptyList(),
                        locationResult = if (queued < recipients.size) {
                            "Queued $queued of ${recipients.size} — daily quota reached"
                        } else if (queued == 1) {
                            "Message queued for 1 recipient"
                        } else {
                            "Messages queued for $queued recipients"
                        },
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSendingLocation = false,
                        locationResult = it.message ?: "Could not send your location",
                    )
                }
        }
    }

    fun clearLocationResult() { _uiState.value = _uiState.value.copy(locationResult = null) }

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
