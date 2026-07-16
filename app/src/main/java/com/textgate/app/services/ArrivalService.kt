package com.textgate.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.textgate.app.R
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.core.utils.RoutineAnalyzer
import com.textgate.app.core.utils.WifiConfig
import com.textgate.app.core.utils.requestWifiScan
import com.textgate.app.core.utils.visibleBssids
import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.links.AnswerLocationRequestsUseCase
import com.textgate.app.domain.usecase.location.RecordArrivalUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Watches for the WiFi networks of saved places being IN RANGE, whether or not
 * the phone joins them. Someone on mobile data all day still passes their home
 * router, so connection was never the right signal.
 *
 * Detection is a scan sweep on a fixed cadence rather than a countdown job per
 * network event: a scan that misses an access point once (which happens often)
 * no longer throws away a countdown that was nearly finished, and a service
 * restart cannot leave a timer behind that never fires.
 */
class ArrivalService : Service() {

    private val userRepo: UserRepository by inject()
    private val recordArrival: RecordArrivalUseCase by inject()
    private val linkRepo: LinkRepository by inject()
    private val answerLocationRequest: AnswerLocationRequestsUseCase by inject()
    private val routineAnalyzer = RoutineAnalyzer()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sweepJob: Job? = null

    // place id → when its network was first heard on this visit
    private val firstSeenAt = mutableMapOf<String, Long>()
    // place id → sweeps in a row the network went missing, because scan results
    // drop an access point at random even while the phone sits next to it.
    private val missedSweeps = mutableMapOf<String, Int>()

    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    // Joining a WiFi network is a strong arrival hint, so sweep immediately
    // instead of waiting out the rest of the cadence. Losing one means nothing:
    // the network can still be in range.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { sweep() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        connectivityManager.registerNetworkCallback(buildNetworkRequest(), networkCallback)
        startSweeping()
        watchLocationRequests()
    }

    override fun onDestroy() {
        isRunning = false
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSweeping() {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            while (isActive) {
                sweep()
                delay(SWEEP_SECONDS * 1000L)
            }
        }
    }

    private suspend fun sweep() {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return
        val user = userRepo.getCurrentUser() ?: return
        requestWifiScan(this)
        val visible = visibleBssids(this)
        val saved = user.places.filter { it.bssid.isNotBlank() }
        Log.d(TAG, "Sweep: ${visible.size} networks in range, ${saved.size} saved places")
        if (visible.isEmpty()) {
            Log.w(TAG, "No networks visible — WiFi scanning or location is likely off")
        }
        val today = DateUtils.todayString()

        saved.forEach { place ->
            if (!place.alertsEnabled) {
                Log.d(TAG, "${place.id}: alerts switched off for this place")
                return@forEach
            }
            if (place.bssid.lowercase() !in visible) {
                val missed = (missedSweeps[place.id] ?: 0) + 1
                missedSweeps[place.id] = missed
                if (missed >= MISSED_SWEEPS_TO_LEAVE && firstSeenAt.remove(place.id) != null) {
                    Log.d(TAG, "${place.id}: out of range, countdown dropped")
                }
                return@forEach
            }
            missedSweeps[place.id] = 0
            if (user.lastArrivalDateByPlace[place.id] == today) {
                Log.d(TAG, "${place.id}: already alerted today")
                return@forEach
            }

            val since = firstSeenAt.getOrPut(place.id) {
                Log.i(TAG, "${place.id}: in range, starting countdown")
                System.currentTimeMillis()
            }
            val arrivalTimes = user.arrivalTimesByPlace[place.id] ?: emptyList()
            val dwellMinutes = place.effectiveDwellMinutes(WifiConfig.STABILITY_MINUTES)
            val waitMinutes = routineAnalyzer.effectiveWait(arrivalTimes, dwellMinutes)
            val elapsedMinutes = (System.currentTimeMillis() - since) / 60_000
            if (elapsedMinutes < waitMinutes) {
                Log.d(TAG, "${place.id}: $elapsedMinutes/$waitMinutes minutes in range")
                return@forEach
            }
            // Suppressing the send must not touch the countdown or the day guard,
            // or the suppression becomes the reason the next real arrival is lost.
            if (place.isQuietAt(DateUtils.minutesOfDay())) {
                Log.d(TAG, "${place.id}: inside its quiet hours, not sending")
                return@forEach
            }

            val routineTriggered = waitMinutes < dwellMinutes
            recordArrival(uid, place.id, routineTriggered)
                .onSuccess { Log.i(TAG, "${place.id}: arrival recorded") }
                .onFailure { Log.w(TAG, "${place.id}: arrival not sent — ${it.message}") }
            // Cleared either way: on success the day guard stops a repeat, and on
            // failure the next attempt waits out the full window instead of
            // retrying every sweep.
            firstSeenAt.remove(place.id)
        }
    }

    // A linked account can ask where we are at any moment, so this listens for
    // the whole life of the service rather than only on WiFi changes. The answer
    // is resolved from what is in range right now, so it has to be read per
    // request.
    private fun watchLocationRequests() {
        scope.launch {
            val uid = userRepo.currentFirebaseUser()?.uid ?: return@launch
            linkRepo.watchPendingRequests(uid).collect { pending ->
                pending.forEach { request ->
                    answerLocationRequest(uid, request, visibleBssids(this@ArrivalService))
                }
            }
        }
    }

    private fun buildNetworkRequest() = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Arrival Monitoring", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Detects home/office WiFi arrivals" }
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("TextGate")
            .setContentText("Arrival monitoring active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        var isRunning: Boolean = false
            private set

        private const val TAG = "TextGateArrival"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "arrival_monitoring"
        // Two minutes keeps every sweep inside Android's scan throttle (four
        // requests per two minutes) while still being far finer than the
        // stability window it is measuring.
        private const val SWEEP_SECONDS = 120
        private const val MISSED_SWEEPS_TO_LEAVE = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ArrivalService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ArrivalService::class.java))
        }
    }
}
