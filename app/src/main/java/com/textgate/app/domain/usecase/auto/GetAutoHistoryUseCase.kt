package com.textgate.app.domain.usecase.auto

import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow

class GetAutoHistoryUseCase(private val smsRepo: SmsRepository) {
    operator fun invoke(uid: String): Flow<List<AutoHistoryEntry>> = smsRepo.getAutoHistory(uid)
}
