package com.textgate.app.domain.repository

import com.textgate.app.domain.model.AlertSubscription
import kotlinx.coroutines.flow.Flow

// Recipient-owned control over automated location alerts. Everything is keyed by
// the recipient's E.164 number, so a relationship survives the recipient not
// having the app yet.
interface AlertRepository {
    suspend fun recordSubscription(
        recipientPhone: String,
        senderUid: String,
        senderName: String,
        senderPhone: String,
    ): Result<Unit>

    // False only when the recipient explicitly turned this sender off.
    suspend fun isAllowed(recipientPhone: String, senderUid: String): Boolean

    suspend fun setSubscribed(
        recipientPhone: String,
        senderUid: String,
        subscribed: Boolean,
    ): Result<Unit>

    fun getSubscriptions(recipientPhone: String): Flow<List<AlertSubscription>>

    // Tells the original sender why their alerts stopped arriving.
    suspend fun notifySenderOfUnsubscribe(
        senderPhone: String,
        recipientName: String,
        recipientUid: String,
    ): Result<Unit>
}
