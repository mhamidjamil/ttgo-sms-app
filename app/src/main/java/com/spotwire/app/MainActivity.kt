package com.spotwire.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.spotwire.app.core.navigation.AppNavGraph
import com.spotwire.app.core.navigation.Screen
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.utils.visibleBssids
import com.spotwire.app.data.local.MonitorLogStore
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.usecase.links.AnswerLocationRequestsUseCase
import com.spotwire.app.services.ArrivalService
import com.spotwire.app.services.IncomingAlertCatchUp
import com.spotwire.app.services.ArrivalWatchdogReceiver
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val userRepo: UserRepository by inject()
    private val prefs: PreferencesDataSource by inject()
    private val linkRepo: LinkRepository by inject()
    private val answerLocationRequest: AnswerLocationRequestsUseCase by inject()
    private val monitorLog: MonitorLogStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restore arrival monitoring if user had it enabled before process death
        // or system kill. This ensures the foreground service survives reboots,
        // battery optimization kills, and Android's foreground service restrictions.
        // Anything that arrived while the app was closed, announced now. This is
        // the backstop: without a push server, opening the app is the one moment
        // an alert is guaranteed to be noticed.
        lifecycleScope.launch {
            runCatching { IncomingAlertCatchUp.run(applicationContext) }
        }

        lifecycleScope.launch {
            if (!prefs.getMonitoringEnabled() || ArrivalService.isRunning) return@launch
            // With every alerting place watched by a fence there is nothing for
            // the service to do, and starting it here would flash a notification
            // on every app open for the seconds it takes to stop itself again.
            // Settings that could not be read fall back to starting it, because
            // a guess must not be the reason detection stays dead.
            val places = runCatching { userRepo.getCurrentUser()?.places }.getOrNull()
            // Opening the app is the one chance to catch a place whose fence is
            // in the wrong spot without waiting out the next watchdog tick, and
            // someone who opens the app is usually someone who noticed nothing
            // arrived.
            if (places != null) {
                ArrivalWatchdogReceiver.rescueMissedFences(
                    this@MainActivity, places, prefs, monitorLog)
            }
            if (places != null && !ArrivalService.needsResidentService(places, this@MainActivity)) {
                return@launch
            }
            ArrivalService.start(this@MainActivity)
        }

        answerPendingLocationRequests()

        val startDestination = if (userRepo.isLoggedIn()) Screen.Send.route else Screen.Login.route
        setContent {
            SpotwireTheme {
                AppNavGraph(startDestination = startDestination)
            }
        }
    }

    // A linked account may have asked where we are while the app was closed and
    // arrival monitoring was off, so the background service never saw the
    // request. Answer those here too, otherwise the asker sits on a request
    // nobody will ever pick up.
    private fun answerPendingLocationRequests() {
        lifecycleScope.launch {
            val uid = userRepo.currentFirebaseUser()?.uid ?: return@launch
            linkRepo.watchPendingRequests(uid).collect { pending ->
                pending.forEach { request ->
                    answerLocationRequest(uid, request, visibleBssids(this@MainActivity))
                }
            }
        }
    }
}
