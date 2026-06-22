package com.textgate.app.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.textgate.app.data.local.MonitorLogStore
import com.textgate.app.domain.model.Place

/**
 * Hands the saved places to Android's own fence watcher, which is the one thing
 * on the phone that can notice an arrival while this app is not running at all.
 * WiFi stays the confirmation: a fence only says "something crossed the line",
 * and the service decides whether that was really an arrival.
 *
 * An object rather than a Koin component because a broadcast receiver has to be
 * able to call it before anything has been injected.
 */
object GeofenceManager {

    /**
     * Fences are delivered to a process that may be dead, so the permission
     * asked for is the background one. Without it Play services accepts the
     * registration on older phones and silently delivers nothing on newer ones.
     */
    fun canRegister(context: Context): Boolean {
        if (!ArrivalService.hasLocationPermission(context)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Replaces the whole registered set with the places that have coordinates
     * and still want alerts. Replacing rather than adding is what stops a fence
     * surviving the place it belonged to being deleted or moved.
     */
    fun refresh(context: Context, places: List<Place>, log: MonitorLogStore) {
        if (!canRegister(context)) {
            Log.w(TAG, "Background location is not granted, no fences registered")
            log.append(MonitorLogStore.Kind.PROBLEM,
                "Geofences need the background location permission, watching by WiFi only")
            return
        }
        val fenced = places.filter { it.hasGeofence && it.alertsEnabled }
        val client = LocationServices.getGeofencingClient(context.applicationContext)
        val target = pendingIntent(context)
        runCatching {
            client.removeGeofences(target).addOnCompleteListener {
                if (fenced.isEmpty()) {
                    Log.i(TAG, "No place has coordinates, nothing to register")
                    return@addOnCompleteListener
                }
                val request = GeofencingRequest.Builder()
                    // No initial trigger. Re-registering while standing inside a
                    // fence would otherwise fire an ENTER on the spot, and that
                    // false alert every morning is the documented reason
                    // geofencing was thrown out in V2.
                    .setInitialTrigger(0)
                    .addGeofences(fenced.map(::fenceFor))
                    .build()
                // The permission can still be taken away between the check above
                // and this callback.
                runCatching {
                    client.addGeofences(request, target)
                        .addOnSuccessListener {
                            log.append(MonitorLogStore.Kind.EVENT,
                                "Geofences registered for ${fenced.size} place(s)")
                        }
                        .addOnFailureListener { error ->
                            Log.w(TAG, "Geofence registration failed", error)
                            log.append(MonitorLogStore.Kind.PROBLEM,
                                "Geofence registration failed, ${reasonFor(error)}")
                        }
                }.onFailure { Log.w(TAG, "Could not register the geofences", it) }
            }
        }.onFailure { Log.w(TAG, "Could not reach the geofencing service", it) }
    }

    // Monitoring switched off has to take the fences with it, or the phone goes
    // on being woken at every boundary for a feature nobody asked to run.
    fun clear(context: Context, log: MonitorLogStore) {
        runCatching {
            LocationServices.getGeofencingClient(context.applicationContext)
                .removeGeofences(pendingIntent(context))
                .addOnSuccessListener {
                    log.append(MonitorLogStore.Kind.EVENT, "Geofences removed")
                }
                .addOnFailureListener { error ->
                    log.append(MonitorLogStore.Kind.PROBLEM,
                        "Geofences could not be removed, ${reasonFor(error)}")
                }
        }.onFailure { Log.w(TAG, "Could not remove the geofences", it) }
    }

    // What a status code means in words. Both the registration failure and the
    // event the system could not deliver arrive as one of these numbers.
    fun statusText(code: Int): String = when (code) {
        GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
            "location is switched off or the phone is in airplane mode"
        GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES ->
            "this phone is already watching as many places as it allows"
        else -> GeofenceStatusCodes.getStatusCodeString(code)
    }

    private fun fenceFor(place: Place): Geofence = Geofence.Builder()
        .setRequestId(place.id)
        .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(
            Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
        )
        // A minute of latency is nothing against a dwell wait counted in
        // minutes, and asking for anything sharper keeps the location hardware
        // busy all day for no gain.
        .setNotificationResponsiveness(RESPONSIVENESS_MILLIS)
        .build()

    // One shared intent for every fence: it is also the handle the whole set is
    // removed by, so the request code has to stay the same forever.
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST_CODE,
        Intent(context.applicationContext, GeofenceEventReceiver::class.java),
        // Mutable because the geofencing service writes the transition and the
        // fences that triggered it into this intent before sending it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun reasonFor(error: Throwable): String {
        val code = (error as? ApiException)?.statusCode
            ?: return error.message ?: "unknown reason"
        return statusText(code)
    }

    private const val TAG = "TextGateGeofence"
    private const val REQUEST_CODE = 2001
    private const val RESPONSIVENESS_MILLIS = 60_000
}
