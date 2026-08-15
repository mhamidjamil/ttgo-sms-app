package com.spotwire.app.presentation.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.data.local.MonitorLogStore
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.domain.model.Place
import com.spotwire.app.domain.model.AlertRoutes
import com.spotwire.app.domain.model.LinkPermissions
import com.spotwire.app.domain.model.PlaceContact
import com.spotwire.app.domain.model.PresenceState
import com.spotwire.app.core.utils.WifiConfig
import com.spotwire.app.domain.model.SettingsChange
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.repository.WhatsAppRepository
import com.spotwire.app.domain.usecase.links.InviteLinkUseCase
import com.spotwire.app.domain.usecase.location.GetPlaceRecipientsUseCase
import com.spotwire.app.domain.usecase.location.SavePlacesUseCase
import com.spotwire.app.domain.usecase.location.SendLocationNowUseCase
import com.spotwire.app.services.ArrivalService
import com.spotwire.app.services.GeofenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

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
    // No WhatsApp gateway AND no text allowance means an arrival is detected and
    // then reaches nobody. Armed but mute is the worst state this app can be in,
    // so it is said on the screen rather than discovered by not being alerted.
    val noDeliveryRoute: Boolean = false,

    // "Send my location now" prompt — non-null place id means it is open.
    val locationPlaceId: String? = null,
    val locationRecipients: List<PlaceContact> = emptyList(),
    val isLoadingRecipients: Boolean = false,
    val isSendingLocation: Boolean = false,
    val locationResult: String? = null,

    // Detection health: the ongoing notification used to claim monitoring was
    // active even after a week of seeing nothing, so a broken feature was
    // invisible. These are what it actually knows.
    val lastObservedAt: Long = 0L,
    val placeStates: Map<String, PresenceState> = emptyMap(),
    // Reaching a recipient inside the app needs a link, and a link needs them to
    // have an account. Both answers, and the invite when they do not.
    val alertRoutes: String = AlertRoutes.BOTH,
    val inviteMessage: String? = null,
    val inviteNumberToShare: String? = null,
    val shareUrl: String = "",
    val isInviting: Boolean = false,

    // Whether the service is alive at this moment, re-read with the health data.
    // The screen used to sample it once, so a service the system had killed
    // hours ago still read as running.
    val serviceRunning: Boolean = false,
)

class SettingsViewModel(
    private val userRepo: UserRepository,
    private val savePlaces: SavePlacesUseCase,
    private val waRepo: WhatsAppRepository,
    private val prefs: PreferencesDataSource,
    private val getPlaceRecipients: GetPlaceRecipientsUseCase,
    private val sendLocationNow: SendLocationNowUseCase,
    private val monitorLog: MonitorLogStore,
    private val inviteLink: InviteLinkUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadUser()
        loadHealth()
    }

    fun loadHealth() {
        viewModelScope.launch {
            val places = _uiState.value.places.ifEmpty { userRepo.getCurrentUser()?.places.orEmpty() }
            _uiState.value = _uiState.value.copy(
                lastObservedAt = prefs.getLastObservedAt(),
                placeStates = places.associate { it.id to prefs.getPresence(it.id).state },
                serviceRunning = ArrivalService.isRunning,
            )
        }
    }

    private fun loadUser() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            val linked = waRepo.isLinked()
            if (user != null) {
                // Copied onto whatever is already there, never a fresh state: the
                // health read runs alongside this one and whichever finishes last
                // used to wipe the other's fields, which is how the card ended up
                // stuck on "nothing checked yet" and "away" for every place.
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    guardianNumber = user.guardianNumber,
                    // toDomain() migrates legacy home/office fields into the
                    // places list, so old accounts land here with both seeded.
                    places = user.places.ifEmpty { Place.defaults() },
                    waConfigured = linked,
                    shareUrl = waRepo.shareUrl(),
                    alertRoutes = user.alertRoutes,
                    noDeliveryRoute = !linked && !user.phoneVerified,
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Could not load settings")
            }
        }
    }

    /**
     * Offers to reach this recipient inside the app instead of by message.
     *
     * If they already have an account, this sends them a link invite and their
     * alerts arrive as a notification, which costs nothing and works in any
     * country. If they do not, there is nothing to link to yet, so the answer is
     * an invitation to install it rather than an error.
     */
    fun inviteRecipient(number: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isInviting = true, inviteMessage = null, inviteNumberToShare = null,
            )
            inviteLink(number, LinkPermissions(autoLocationUpdates = true), countryIso = "")
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isInviting = false,
                        inviteMessage = "Invite sent to $it. Once they accept, their alerts arrive in the app.",
                    )
                }
                .onFailure { failure ->
                    val noAccount = failure.message.orEmpty().contains("No Spotwire account")
                    _uiState.value = _uiState.value.copy(
                        isInviting = false,
                        inviteMessage =
                            if (noAccount) "They do not have Spotwire yet. Send them the app."
                            else failure.message ?: "Could not send the invite",
                        inviteNumberToShare = if (noAccount) number else null,
                    )
                }
        }
    }

    fun setAlertRoutes(routes: String) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        _uiState.value = _uiState.value.copy(alertRoutes = routes)
        viewModelScope.launch { userRepo.saveAlertRoutes(uid, routes) }
    }

    fun clearInvite() {
        _uiState.value = _uiState.value.copy(inviteMessage = null, inviteNumberToShare = null)
    }

    // Whole-place update — the place editor dialog applies its draft through
    // this (label, message, contacts) and the WiFi picker updates bssid/label.
    // The accuracy comes with it only when the point was captured from the
    // phone; coordinates typed by hand have none to report.
    fun updatePlace(updated: Place, fixAccuracyMeters: Float? = null) {
        val previous = _uiState.value.places.firstOrNull { it.id == updated.id }
        val places = _uiState.value.places.map { if (it.id == updated.id) updated else it }
        _uiState.value = _uiState.value.copy(places = places)
        val networksChanged = previous != null &&
            previous.savedBssids.toSet() != updated.savedBssids.toSet()
        persistPlaces(places, clearStateFor = updated.id.takeIf { networksChanged })
        if (previous != null) {
            logAlertingChanges(previous, updated)
            logLocationChange(previous, updated, fixAccuracyMeters)
        }
    }

    // The monitoring log is where a missed arrival is diagnosed, so the moment a
    // place gained or lost its point on the map has to be readable there. How
    // good the fix was belongs in the same row: a place pinned from a 300 m fix
    // explains a fence that never fires, and nothing else records that.
    private fun logLocationChange(before: Place, after: Place, fixAccuracyMeters: Float?) {
        if (before.latitude == after.latitude && before.longitude == after.longitude &&
            before.radiusMeters == after.radiusMeters) return
        val name = after.label.ifBlank { after.id }
        val message = if (after.hasGeofence) {
            "$name: location set to ${String.format(Locale.US, "%.6f", after.latitude)}, " +
                "${String.format(Locale.US, "%.6f", after.longitude)}, " +
                "radius ${after.radiusMeters} m" +
                (fixAccuracyMeters?.let { " (fix accurate to ${it.toInt()} m)" } ?: "")
        } else {
            "$name: location cleared"
        }
        viewModelScope.launch(Dispatchers.IO) {
            monitorLog.append(MonitorLogStore.Kind.EVENT, message)
        }
    }

    // Only the settings that decide whether an alert goes out are audited, so a
    // place that quietly stopped alerting can be traced to the change that did it.
    private fun logAlertingChanges(before: Place, after: Place) {
        val name = after.label.ifBlank { after.id }
        val changes = buildList {
            if (before.alertsEnabled != after.alertsEnabled) add(SettingsChange(
                field = "$name alerts",
                oldValue = if (before.alertsEnabled) "on" else "off",
                newValue = if (after.alertsEnabled) "on" else "off",
            ))
            val wasWait = before.effectiveDwellMinutes(WifiConfig.STABILITY_MINUTES)
            val nowWait = after.effectiveDwellMinutes(WifiConfig.STABILITY_MINUTES)
            if (wasWait != nowWait) add(SettingsChange(
                field = "$name wait before alerting",
                oldValue = "$wasWait min",
                newValue = "$nowWait min",
            ))
            if (before.quietFrom != after.quietFrom || before.quietTo != after.quietTo) add(SettingsChange(
                field = "$name quiet hours",
                oldValue = quietLabel(before),
                newValue = quietLabel(after),
            ))
        }
        if (changes.isEmpty()) return
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch { userRepo.logSettingsChanges(uid, changes) }
    }

    private fun quietLabel(place: Place): String =
        if (place.quietFrom.isBlank() || place.quietTo.isBlank()) "none"
        else "${place.quietFrom}-${place.quietTo}"

    // A brand new place has nothing worth writing yet; it persists on first edit.
    fun addPlace() {
        val newPlace = Place(id = "place_${System.currentTimeMillis()}", label = "")
        _uiState.value = _uiState.value.copy(places = _uiState.value.places + newPlace)
    }

    // Any place can go, including the seeded home/office pair: the people who
    // asked had places they never used sitting undeletable at the top.
    fun removePlace(id: String) {
        val places = _uiState.value.places.filterNot { it.id == id }
        _uiState.value = _uiState.value.copy(places = places)
        persistPlaces(places, clearStateFor = id)
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
    private fun persistPlaces(places: List<Place>, clearStateFor: String? = null) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        viewModelScope.launch {
            // A place whose networks changed is a different place as far as the
            // countdown is concerned. Keeping the old visit state is how a
            // renamed or re-captured place never alerted again: it stayed marked
            // as already here, so every sweep decided the arrival was old news.
            clearStateFor?.let { prefs.clearPlaceState(it) }
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveSuccess = false)
            savePlaces.savePlacesOnly(uid, places.filter(::isWorthSaving))
                .onSuccess {
                    // The service caches the place list for hours, so without
                    // this an edit made on this phone would not reach detection
                    // until the next refresh.
                    ArrivalService.notePlacesChanged()
                    _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
                }
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

    // Switching monitoring off has to take the registered fences with it, or the
    // system goes on waking the app at every boundary for a feature the user
    // just turned off.
    fun clearGeofences(context: Context) = GeofenceManager.clear(context, monitorLog)

    /**
     * The first line of "Test detection here". Detection can be perfect while
     * sending is impossible, and that split is exactly what the field logs
     * showed: the place confirmed, then the alert failed on the account read.
     * So the button now proves the whole chain, not just the WiFi.
     */
    suspend fun accountHealthLine(): String {
        userRepo.currentFirebaseUser() ?: return "Not signed in: nothing can be sent. Sign in again."
        val fresh = withTimeoutOrNull(10_000) { userRepo.getCurrentUser() }
            ?: return "Signed in, but your account could not be read from the server just now. " +
                "An arrival at this moment would fall back to the copy already on the phone."
        val guardian = if (fresh.guardianNumber.isBlank()) "no guardian number set" else "guardian number set"
        return "Account readable: ${fresh.places.size} place(s), $guardian."
    }

    // The all-the-time location grant is the one thing fences cannot be
    // registered without, so the moment it arrives they are put in place rather
    // than waiting for the next service start.
    fun refreshGeofences(context: Context, places: List<Place>) {
        viewModelScope.launch {
            prefs.setGeofencesRefreshedAt(System.currentTimeMillis())
            GeofenceManager.refresh(context, places, monitorLog)
        }
    }

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
