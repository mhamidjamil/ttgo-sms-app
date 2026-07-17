package com.textgate.app.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.textgate.app.data.local.PreferencesDataSource
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

    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.action ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!prefs.getMonitoringEnabled()) return@launch
                scheduleNextCheck(appContext)
                if (ArrivalService.isRunning) return@launch
                Log.i(TAG, "Restarting arrival monitoring after $reason")
                runCatching { ArrivalService.start(appContext) }
                    .onFailure { Log.w(TAG, "Could not restart monitoring: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TextGateWatchdog"
        const val ACTION_CHECK = "com.textgate.app.ARRIVAL_WATCHDOG"
        private const val CHECK_INTERVAL_MILLIS = 15 * 60 * 1000L

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
