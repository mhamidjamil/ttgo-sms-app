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
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.textgate.app.R
import com.textgate.app.core.utils.RoutineAnalyzer
import com.textgate.app.core.utils.WifiConfig
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.location.RecordArrivalUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ArrivalService : Service() {

    private val userRepo: UserRepository by inject()
    private val recordArrival: RecordArrivalUseCase by inject()
    private val routineAnalyzer = RoutineAnalyzer()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stabilityJobs = mutableMapOf<String, Job>() // location → countdown job

    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private val wifiManager by lazy {
        applicationContext.getSystemService(WifiManager::class.java)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { checkAndStartTimer() }
        override fun onLost(network: Network) { cancelAllTimers() }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        connectivityManager.registerNetworkCallback(buildNetworkRequest(), networkCallback)
        // Check current WiFi immediately in case already connected
        checkAndStartTimer()
    }

    override fun onDestroy() {
        isRunning = false
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkAndStartTimer() {
        scope.launch {
            val currentBssid = getCurrentBssid() ?: return@launch
            val user = userRepo.getCurrentUser() ?: return@launch
            val uid = userRepo.currentFirebaseUser()?.uid ?: return@launch

            val location = when (currentBssid) {
                user.homeBssid.ifBlank { null } -> "home"
                user.officeBssid.ifBlank { null } -> "office"
                else -> return@launch
            }

            if (stabilityJobs.containsKey(location)) return@launch // timer already running

            val arrivalTimes = if (location == "home") user.arrivalHomeTimes else user.arrivalOfficeTimes
            val waitMinutes = routineAnalyzer.effectiveWait(arrivalTimes, WifiConfig.STABILITY_MINUTES)
            val routineTriggered = waitMinutes < WifiConfig.STABILITY_MINUTES

            stabilityJobs[location] = launch {
                delay(waitMinutes * 60_000L)
                // Verify still on same BSSID after stability period
                if (getCurrentBssid() == currentBssid) {
                    recordArrival(uid, location, routineTriggered)
                }
                stabilityJobs.remove(location)
            }
        }
    }

    private fun cancelAllTimers() {
        stabilityJobs.values.forEach { it.cancel() }
        stabilityJobs.clear()
    }

    @Suppress("DEPRECATION")
    private fun getCurrentBssid(): String? = try {
        wifiManager.connectionInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }
    } catch (_: Exception) {
        null
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

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "arrival_monitoring"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ArrivalService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ArrivalService::class.java))
        }
    }
}
