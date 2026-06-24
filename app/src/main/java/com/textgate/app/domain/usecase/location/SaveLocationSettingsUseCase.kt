package com.textgate.app.domain.usecase.location

import com.textgate.app.domain.repository.UserRepository

class SaveLocationSettingsUseCase(private val userRepo: UserRepository) {
    suspend operator fun invoke(
        uid: String,
        guardianNumber: String,
        homeBssid: String,
        homeLabel: String,
        officeBssid: String,
        officeLabel: String,
    ): Result<Unit> = userRepo.saveLocationSettings(
        uid, guardianNumber, homeBssid, homeLabel, officeBssid, officeLabel
    )
}
