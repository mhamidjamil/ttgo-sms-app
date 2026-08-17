package com.spotwire.app.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.spotwire.app.core.utils.cachedVisibleAccessPoints
import com.spotwire.app.core.utils.cachedVisibleBssids
import com.spotwire.app.core.utils.canScanWifi
import com.spotwire.app.core.utils.resolvePresence
import com.spotwire.app.data.local.MonitorLogStore
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.data.local.VisitLogStore
import com.spotwire.app.domain.model.Place
import com.spotwire.app.domain.model.PresenceState
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Brings arrival monitoring back by itself.
 *
 * Before this, the service was only ever started from the settings screen, so a
 * phone restart left detection dead until the app happened to be opened, which
 * could be the next day. The two ways it dies are a restart and this phone
 * family's battery manager killing the service, and neither used to be noticed.
 *
 * Starting a foreground service from the background is normally refused, so the
 * two triggers here are the ones the platform explicitly allows: the boot
 * broadcast, and a repeating check that only gets through once the user has
 * granted the battery optimisation exemption offered on the settings screen.
 */
class ArrivalWatchdogReceiver : BroadcastReceiver(), KoinComponent {

    private val prefs: PreferencesDataSource by inject()
    private val userRepo: UserRepository by inject()
    private val monitorLog: MonitorLogStore by inject()
    private val visitLog: VisitLogStore by inject()
    private val linkRepo: LinkRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.action ?: return
        val appContext = context.applicationContext
        // LOCKED_BOOT_COMPLETED arrives before the user has unlocked the phone
        // for the first time, and the saved settings live in credential-encrypted
        // storage that does not exist yet. Reading them there throws and takes
        // the process down. BOOT_COMPLETED follows after the unlock, which is the
        // earliest this can do anything useful anyway.
        if (appContext.getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
            Log.i(TAG, "Ignoring $reason: waiting for the phone to be unlocked")
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Checked before the monitoring guard: a person can be receiving
                // somebody else's alerts without watching any places themselves,
                // and this tick is the only chance they get to hear about one
                // while the app is closed.
                runCatching { IncomingAlertCatchUp.run(appContext) }
                    .onFailure { Log.w(TAG, "incoming alert catch-up failed", it) }
                if (!prefs.getMonitoringEnabled()) return@launch
                scheduleNextCheck(appContext)
                // Null means the settings could not be read at all, which is not
                // the same as having no places: the checks below fall back to
                // the old unconditional behaviour rather than guess from it.
                val places = runCatching { userRepo.getCurrentUser()?.places }
                    .onFailure { Log.w(TAG, "Could not read the saved places", it) }
                    .getOrNull()
                // A reboot wipes every fence the system was watching, and
                // nothing else puts them back. Done before the running check
                // because a service that survived says nothing about the fences.
                // The stale check is for the quieter failure: Play services
                // dropping a fence with nothing to say about it, which this
                // undoes within a few hours instead of never.
                val staleFences =
                    System.currentTimeMillis() - prefs.getGeofencesRefreshedAt() > FENCE_REFRESH_MILLIS
                if (places != null && (reason == Intent.ACTION_BOOT_COMPLETED || staleFences)) {
                    GeofenceManager.refresh(appContext, places, monitorLog)
                    prefs.setGeofencesRefreshedAt(System.currentTimeMillis())
                }
                if (places != null) rescueMissedFences(appContext, places, prefs, monitorLog)
                // With every place watched by a fence the service is stopped
                // most of the time, so this tick is the only thing left that can
                // put anything on the timeline. It reads the scan cache, which
                // costs no scan budget, so it is safe from an alarm.
                if (places != null) noteWhereabouts(appContext, places)
                pruneOldVisits(appContext)
                if (ArrivalService.isRunning) return@launch
                // Somebody waiting on a location request is a reason to start,
                // whatever the fences are doing. There is no push here, so this
                // tick is the only thing that can wake a phone whose app is
                // closed: without it a guardian's ask would sit unanswered until
                // the app was next opened by hand.
                if (hasWaitingRequest()) {
                    Log.i(TAG, "Starting monitoring: a linked account is waiting on a location request")
                    ArrivalService.start(appContext)
                    return@launch
                }
                // Nothing to bring back when every alerting place is watched by
                // a fence: starting here would only show a notification for the
                // seconds the service takes to stop itself again.
                if (places != null && !ArrivalService.needsResidentService(places, appContext)) {
                    Log.i(TAG, "Not restarting monitoring after $reason: the fences are watching")
                    return@launch
                }
                Log.i(TAG, "Restarting arrival monitoring after $reason")
                ArrivalService.start(appContext)
            } catch (e: Exception) {
                // Nothing here may escape: an uncaught throw in a boot receiver
                // is a crash dialog the moment the phone starts.
                Log.w(TAG, "Could not restart monitoring after $reason", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * One sample for the timeline. Nothing is written when the phone cannot look
     * at all: a hole in the record is the honest answer, and crediting the hours
     * to wherever it last was is not.
     */
    private suspend fun noteWhereabouts(context: Context, places: List<Place>) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        if (!canScanWifi(context)) return
        val visible = cachedVisibleAccessPoints(context)
        if (visible.isEmpty()) return
        // The same rule the sweep uses, so the two never disagree about where
        // the phone was at the same minute. A place with no saved networks can
        // only be reported by its fence, which starts the service instead.
        val winner = resolvePresence(places, visible).winner
        runCatching { ArrivalService.notePosition(uid, winner, visitLog, userRepo) }
            .onFailure { Log.w(TAG, "Could not record where the phone is", it) }
    }

    // A read, not a listener: this runs in a receiver with about ten seconds to
    // live, so it takes the first list the query produces and gets out.
    private suspend fun hasWaitingRequest(): Boolean {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return false
        return runCatching {
            withTimeoutOrNull(5_000) { linkRepo.watchPendingRequests(uid).first() }
                ?.any { !it.stopRequested } == true
        }.getOrDefault(false)
    }

    /**
     * Drops the account's stays past the month. Nothing runs on the server, so
     * this is the only thing keeping them from growing forever; once a day is
     * plenty for a window measured in weeks.
     */
    private suspend fun pruneOldVisits(context: Context) {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        val store = context.getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - store.getLong(KEY_VISITS_PRUNED_AT, 0L) < PRUNE_INTERVAL_MILLIS) return
        store.edit().putLong(KEY_VISITS_PRUNED_AT, now).apply()
        userRepo.prunePlaceVisits(uid, now - VISIT_RETENTION_MILLIS)
            .onFailure { Log.w(TAG, "Could not drop stays past the month", it) }
    }

    companion object {
        private const val TAG = "SpotwireWatchdog"
        const val ACTION_CHECK = "com.spotwire.app.ARRIVAL_WATCHDOG"
        private const val CHECK_INTERVAL_MILLIS = 15 * 60 * 1000L
        // How old the registered fences may get before they are put back on
        // trust. Long enough that the tick stays free, short enough that a fence
        // the system quietly dropped is not missing for a whole day.
        private const val FENCE_REFRESH_MILLIS = 6 * 60 * 60 * 1000L

        // How long one place waits between two of these checks. A fence sitting
        // at the wrong coordinates never fires, so without a floor here it would
        // buy a validation session every fifteen minutes, all day; ninety
        // minutes is still well inside the same visit for any place worth
        // alerting about.
        private const val MISSED_FENCE_COOLDOWN_MILLIS = 90 * 60 * 1000L

        /**
         * The net under a fence that never fires. Coordinates captured from one
         * bad location fix put the fence hundreds of metres from the place, and
         * in geofence mode nothing else is looking: the service has stopped
         * itself, so the place is silently never detected however loud its own
         * networks are. Hearing those networks while the place is marked away is
         * the cheap sign of exactly that, and of a fence Play services dropped
         * or a crossing made while location was off.
         *
         * Only fenced places are checked. A WiFi-only place is already covered
         * by the resident service, which is running precisely because of it.
         */
        suspend fun rescueMissedFences(
            context: Context,
            places: List<Place>,
            prefs: PreferencesDataSource,
            monitorLog: MonitorLogStore,
        ) {
            val audible = cachedVisibleBssids(context)
            if (audible.isEmpty()) return
            places.forEach { place ->
                if (!place.alertsEnabled || !place.hasGeofence) return@forEach
                if (place.savedBssids.none { it in audible }) return@forEach
                if (prefs.getPresence(place.id).state != PresenceState.AWAY) return@forEach
                if (!claimMissedFenceCheck(context, place.id)) return@forEach
                // Without the battery exemption the platform refuses a service
                // start from an alarm, and that refusal must not take the fence
                // refresh and the restart check down with it.
                val started = runCatching { ArrivalService.startMissedFenceCheck(context, place.id) }
                    .onFailure { Log.w(TAG, "${place.id}: could not start the missed-fence check", it) }
                    .isSuccess
                if (started) monitorLog.append(MonitorLogStore.Kind.EVENT,
                    "${place.label.ifBlank { place.id }}: its WiFi is audible but no fence fired, checking now")
            }
        }

        /**
         * Takes this place's turn if the cooldown has passed. Plain
         * SharedPreferences on purpose: this runs in a receiver, where anything
         * held in memory is gone before the next tick, and it is one timestamp
         * per place that nothing outside this file ever reads.
         */
        private fun claimMissedFenceCheck(context: Context, placeId: String): Boolean {
            val store = context.getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
            val key = "missed_fence_check_$placeId"
            val now = System.currentTimeMillis()
            // A stamp in the future can only come from the clock being moved
            // back, and refusing forever on that would be the same blindness
            // this exists to end.
            if (store.getLong(key, 0L) in (now - MISSED_FENCE_COOLDOWN_MILLIS)..now) return false
            store.edit().putLong(key, now).apply()
            return true
        }

        private const val WATCHDOG_PREFS = "spotwire_watchdog"
        private const val KEY_VISITS_PRUNED_AT = "visits_pruned_at"
        // A window measured in weeks does not need trimming every quarter hour.
        private const val PRUNE_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
        // A month on the account, against the seven days the phone itself keeps.
        // Long enough to answer "where was he last Tuesday" after a reinstall,
        // short enough that a location history is not kept indefinitely.
        private const val VISIT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000L

        // Inexact on purpose: the system batches it with other wake-ups, so the
        // check costs nothing extra. Catching a killed service ten minutes late
        // is still far better than catching it when the app is next opened.
        fun scheduleNextCheck(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + CHECK_INTERVAL_MILLIS,
                CHECK_INTERVAL_MILLIS,
                checkIntent(context),
            )
        }

        fun cancelChecks(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(checkIntent(context))
        }

        private fun checkIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ArrivalWatchdogReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
