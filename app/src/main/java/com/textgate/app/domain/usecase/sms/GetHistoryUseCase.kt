package com.textgate.app.domain.usecase.sms

import com.textgate.app.domain.model.HistoryEntry
import com.textgate.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow

class GetHistoryUseCase(private val repo: SmsRepository) {
    operator fun invoke(uid: String): Flow<List<HistoryEntry>> = repo.getHistory(uid)
}
