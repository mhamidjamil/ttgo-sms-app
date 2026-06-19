package com.spotwire.app.domain.usecase.alerts

import com.spotwire.app.domain.model.AlertSubscription
import com.spotwire.app.domain.repository.AlertRepository

// Turning a sender off is the recipient's own decision, so it is a write to the
// recipient's own subscription document. The sender is told afterwards, so they
// know why the alerts stopped rather than assuming delivery is broken. The
// relationship is deactivated, never deleted, so history stays auditable.
class UnsubscribeFromSenderUseCase(private val alertRepo: AlertRepository) {
    suspend operator fun invoke(
        myPhone: String,
        myName: String,
        myUid: String,
        subscription: AlertSubscription,
    ): Result<Unit> {
        val result = alertRepo.setSubscribed(myPhone, subscription.senderUid, subscribed = false)
        if (result.isSuccess && subscription.senderPhone.isNotBlank()) {
            // Best effort: the opt-out already took effect, so a failed courtesy
            // notice must not report the unsubscribe itself as failed.
            alertRepo.notifySenderOfUnsubscribe(subscription.senderPhone, myName, myUid)
        }
        return result
    }
}
