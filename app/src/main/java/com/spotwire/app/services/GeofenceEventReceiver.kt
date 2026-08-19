package com.spotwire.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.spotwire.app.data.local.MonitorLogStore
import org.koin.core.context.GlobalContext

/**
 * Where an arrival now begins. Android delivers this even with the app closed
 * and the process dead, which is exactly the case WiFi sweeps could never cover.
 *
 * Nothing is decided here: a crossing is evidence, not an arrival. ENTER opens a
 * validation session in the service and EXIT asks it to check the departure.
 */
class GeofenceEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        // A fence can fire before anything has been injected, so the log is a
        // best effort rather than something to crash the receiver over.
        val log = runCatching { GlobalContext.get().get<MonitorLogStore>() }.getOrNull()
        if (event.hasError()) {
            Log.w(TAG, "Geofence event carried error ${event.errorCode}")
            log?.append(MonitorLogStore.Kind.PROBLEM,
                "Geofence event could not be read, ${GeofenceManager.statusText(event.errorCode)}")
            return
        }
        val appContext = context.applicationContext
        // The location fix that crossed the fence carries its own wall clock,
        // and delivery can run minutes behind it while the phone dozes, so the
        // fix's time is the crossing time. Delivery time is only the fallback,
        // and the fix's clock is trusted only when it reads sane: not in the
        // future, and not so old that it describes some earlier trip.
        val now = System.currentTimeMillis()
        val at = event.triggeringLocation?.time
            ?.takeIf { it in (now - 30 * 60 * 1000L)..now } ?: now
        val transition = event.geofenceTransition
        event.triggeringGeofences.orEmpty().forEach { fence ->
            val placeId = fence.requestId
            when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    log?.append(MonitorLogStore.Kind.EVENT, "Geofence entered: $placeId")
                    // A geofence transition is on the platform's own list of
                    // reasons a foreground service may start from the
                    // background, so this is allowed with the app closed.
                    runCatching { ArrivalService.startValidation(appContext, placeId, at) }
                        .onFailure { Log.w(TAG, "Could not start the validation session", it) }
                }
                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    log?.append(MonitorLogStore.Kind.EVENT, "Geofence exited: $placeId")
                    runCatching { ArrivalService.startDeparture(appContext, placeId) }
                        .onFailure { Log.w(TAG, "Could not start the departure check", it) }
                }
                else -> Log.d(TAG, "Ignoring geofence transition $transition")
            }
        }
    }

    companion object {
        private const val TAG = "SpotwireGeofence"
    }
}
