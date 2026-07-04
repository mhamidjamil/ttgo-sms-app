package com.textgate.app.domain.usecase.location

import com.textgate.app.domain.model.Place
import com.textgate.app.domain.repository.UserRepository

class SavePlacesUseCase(private val userRepo: UserRepository) {
    suspend operator fun invoke(
        uid: String,
        guardianNumber: String,
        places: List<Place>,
    ): Result<Unit> = userRepo.savePlacesSettings(uid, guardianNumber, places)
}
