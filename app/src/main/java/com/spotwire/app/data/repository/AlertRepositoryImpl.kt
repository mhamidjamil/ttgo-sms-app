package com.spotwire.app.data.repository

import android.util.Log
import com.spotwire.app.data.firebase.FirestoreDataSource
import com.spotwire.app.domain.model.AlertSubscription
import com.spotwire.app.domain.repository.AlertRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "AlertRepository"

class AlertRepositoryImpl(private val firestore: FirestoreDataSource) : AlertRepository {

    override suspend fun recordSubscription(
        recipientPhone: String,
        senderUid: String,
        senderName: String,
        senderPhone: String,
    ) = firestore.recordAlertSubscription(recipientPhone, senderUid, senderName, senderPhone)

    // A failed read must not silently mute a recipient, so the fallback is to
    // allow. The only thing that stops an alert is a stored, explicit opt-out.
    override suspend fun isAllowed(recipientPhone: String, senderUid: String): Boolean =
        firestore.isAlertAllowed(recipientPhone, senderUid).getOrElse { error ->
            Log.w(TAG, "subscription check failed for $recipientPhone, allowing", error)
            true
        }

    override suspend fun setSubscribed(
        recipientPhone: String,
        senderUid: String,
        subscribed: Boolean,
    ) = firestore.setAlertSubscribed(recipientPhone, senderUid, subscribed)

    override fun getSubscriptions(recipientPhone: String): Flow<List<AlertSubscription>> =
        firestore.getAlertSubscriptions(recipientPhone).map { list -> list.map { it.toDomain() } }

    override suspend fun notifySenderOfUnsubscribe(
        senderPhone: String,
        recipientName: String,
        recipientUid: String,
    ): Result<Unit> = firestore.enqueueSystemNotice(
        phoneNumber = senderPhone,
        message = "$recipientName has unsubscribed from your automated location updates.",
        enqueBy = "app:$recipientUid:unsub",
    )
}
