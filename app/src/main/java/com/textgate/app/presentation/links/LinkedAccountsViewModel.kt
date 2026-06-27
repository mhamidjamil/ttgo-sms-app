package com.textgate.app.presentation.links

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.model.AccountLink
import com.textgate.app.domain.model.LinkPermissions
import com.textgate.app.domain.model.LinkState
import com.textgate.app.domain.model.LocationRequest
import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.links.InviteLinkUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class LinkedAccountsUiState(
    val links: List<AccountLink> = emptyList(),
    val isLoading: Boolean = true,
    val isInviting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val busyUids: Set<String> = emptySet(),
    // uid of the account whose location was asked for, plus the answer so far.
    val awaitingLocationFor: String? = null,
    val locationAnswer: String? = null,
)

class LinkedAccountsViewModel(
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
    private val inviteLink: InviteLinkUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkedAccountsUiState())
    val uiState: StateFlow<LinkedAccountsUiState> = _uiState.asStateFlow()

    private var myUid: String = ""
    private var myName: String = ""
    private var answerWatcher: Job? = null

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            if (user == null) {
                _uiState.value = LinkedAccountsUiState(isLoading = false, error = "Could not load your profile")
                return@launch
            }
            myUid = user.uid
            myName = user.name
            linkRepo.getLinks(user.uid)
                .onEach { links ->
                    _uiState.value = _uiState.value.copy(
                        // Pending invites first — they need an answer.
                        links = links.sortedBy { it.state.ordinal },
                        isLoading = false,
                    )
                }
                .launchIn(this)
        }
    }

    fun invite(rawPhone: String, permissions: LinkPermissions) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isInviting = true, error = null, message = null)
            inviteLink(rawPhone, permissions)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isInviting = false,
                        message = "Invite sent to $it. They have to accept it before anything is shared.",
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isInviting = false,
                        error = it.message ?: "Could not send the invite",
                    )
                }
        }
    }

    // Accepting flips both sides to active; declining marks only my own side so
    // the inviter still sees what happened on theirs.
    fun respond(link: AccountLink, accept: Boolean) {
        viewModelScope.launch {
            markBusy(link.otherUid, true)
            val state = if (accept) LinkState.ACTIVE else LinkState.DECLINED
            linkRepo.setState(myUid, link.otherUid, state, alsoUpdateOtherSide = accept)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "Could not respond") }
            markBusy(link.otherUid, false)
        }
    }

    fun setPermissions(link: AccountLink, permissions: LinkPermissions) {
        viewModelScope.launch {
            markBusy(link.otherUid, true)
            linkRepo.setPermissions(myUid, link.otherUid, permissions)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "Could not save permissions") }
            markBusy(link.otherUid, false)
        }
    }

    fun remove(link: AccountLink) {
        viewModelScope.launch {
            markBusy(link.otherUid, true)
            linkRepo.remove(myUid, link.otherUid)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "Could not remove") }
            markBusy(link.otherUid, false)
        }
    }

    // Asks the other device where it is. The answer arrives asynchronously, so
    // the request document itself is watched rather than polled.
    fun requestLocation(link: AccountLink) {
        answerWatcher?.cancel()
        _uiState.value = _uiState.value.copy(
            awaitingLocationFor = link.otherUid,
            locationAnswer = null,
            error = null,
        )
        viewModelScope.launch {
            linkRepo.requestLocation(link.otherUid, myUid, myName)
                .onSuccess { requestId ->
                    answerWatcher = linkRepo.watchLocationRequest(link.otherUid, requestId)
                        .onEach { request ->
                            when (request?.status) {
                                LocationRequest.ANSWERED ->
                                    _uiState.value = _uiState.value.copy(locationAnswer = request.answer)
                                LocationRequest.DENIED ->
                                    _uiState.value = _uiState.value.copy(
                                        locationAnswer = "They have not allowed on-demand location requests.",
                                    )
                            }
                        }
                        .launchIn(viewModelScope)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        awaitingLocationFor = null,
                        error = it.message ?: "Could not send the request",
                    )
                }
        }
    }

    fun dismissLocationAnswer() {
        answerWatcher?.cancel()
        answerWatcher = null
        _uiState.value = _uiState.value.copy(awaitingLocationFor = null, locationAnswer = null)
    }

    fun clearMessages() { _uiState.value = _uiState.value.copy(error = null, message = null) }

    private fun markBusy(uid: String, busy: Boolean) {
        val ids = _uiState.value.busyUids
        _uiState.value = _uiState.value.copy(busyUids = if (busy) ids + uid else ids - uid)
    }
}
