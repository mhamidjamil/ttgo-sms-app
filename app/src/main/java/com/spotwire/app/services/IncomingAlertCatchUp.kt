package com.spotwire.app.services

import android.content.Context
import android.util.Log
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Announces alerts that arrived while nobody was looking.
 *
 * Without a server holding a push credential there is no way to wake this phone
 * the moment somebody else arrives somewhere, so instead it is checked whenever
 * the app is already awake: on opening, and on the fifteen-minute alarm that
 * already exists to keep monitoring alive. An alert therefore lands immediately
 * while the app is running, within about fifteen minutes otherwise, and at the
 * latest when the app is next opened. That is worth saying plainly on screen
 * rather than implying anything faster.
 */
object IncomingAlertCatchUp : KoinComponent {

    private const val TAG = "IncomingAlerts"

    private val userRepo: UserRepository by inject()
    private val linkRepo: LinkRepository by inject()
    private val prefs: PreferencesDataSource by inject()

    suspend fun run(context: Context) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        val unseen = linkRepo.unseenIncomingAlerts(uid).getOrElse {
            Log.w(TAG, "could not read incoming alerts: ${it.message}")
            return
        }
        if (unseen.isEmpty()) return

        val alreadyAnnouncedUpTo = prefs.getLastAlertNotifiedAt()
        val fresh = unseen.filter { (it.sentAt?.toDate()?.time ?: 0L) > alreadyAnnouncedUpTo }
        if (fresh.isEmpty()) return

        fresh.forEach { alert ->
            DeliveryNotifier.notifyIncomingAlert(
                context = context,
                senderName = alert.senderName.ifBlank { alert.senderPhone },
                message = alert.message,
                id = alert.id.hashCode().and(0xFF),
            )
        }
        val newest = fresh.maxOf { it.sentAt?.toDate()?.time ?: 0L }
        prefs.setLastAlertNotifiedAt(newest)
        Log.i(TAG, "announced ${fresh.size} incoming alert(s)")
    }
}
