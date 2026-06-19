package com.spotwire.app.domain.usecase.auto

import com.spotwire.app.domain.model.AutoHistoryEntry
import com.spotwire.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow

class GetAutoHistoryUseCase(private val smsRepo: SmsRepository) {
    operator fun invoke(uid: String): Flow<List<AutoHistoryEntry>> = smsRepo.getAutoHistory(uid)
}
