package com.spotwire.app.domain.usecase.sms

import com.spotwire.app.domain.model.HistoryEntry
import com.spotwire.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow

class GetHistoryUseCase(private val repo: SmsRepository) {
    operator fun invoke(uid: String): Flow<List<HistoryEntry>> = repo.getHistory(uid)
}
