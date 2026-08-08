package com.textgate.app.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.textgate.app.data.local.MonitorLogStore
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                if (ArrivalService.isRunning) return@launch
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

    companion object {
        private const val TAG = "TextGateWatchdog"
        const val ACTION_CHECK = "com.textgate.app.ARRIVAL_WATCHDOG"
        private const val CHECK_INTERVAL_MILLIS = 15 * 60 * 1000L
        // How old the registered fences may get before they are put back on
        // trust. Long enough that the tick stays free, short enough that a fence
        // the system quietly dropped is not missing for a whole day.
        private const val FENCE_REFRESH_MILLIS = 6 * 60 * 60 * 1000L

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
