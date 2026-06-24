package com.textgate.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.auth.SendVerificationEmailUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val user: User? = null,
    val effectiveQuota: Int = 0,
    val isLoading: Boolean = true,
    val verificationSent: Boolean = false,
    val error: String? = null,
)

class ProfileViewModel(
    private val userRepo: UserRepository,
    private val getEffectiveQuota: GetEffectiveQuotaUseCase,
    private val sendVerification: SendVerificationEmailUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val user = userRepo.getCurrentUser()
            val quota = user?.let { getEffectiveQuota(it) } ?: 0
            _uiState.value = ProfileUiState(user = user, effectiveQuota = quota, isLoading = false)
        }
    }

    fun resendVerification() {
        viewModelScope.launch {
            sendVerification()
                .onSuccess { _uiState.value = _uiState.value.copy(verificationSent = true) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    suspend fun signOut() { userRepo.signOut() }
}
