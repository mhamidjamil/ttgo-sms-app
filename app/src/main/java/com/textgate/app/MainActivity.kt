package com.textgate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.textgate.app.core.navigation.AppNavGraph
import com.textgate.app.core.navigation.Screen
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.core.utils.visibleBssids
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.links.AnswerLocationRequestsUseCase
import com.textgate.app.services.ArrivalService
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val userRepo: UserRepository by inject()
    private val prefs: PreferencesDataSource by inject()
    private val linkRepo: LinkRepository by inject()
    private val answerLocationRequest: AnswerLocationRequestsUseCase by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restore arrival monitoring if user had it enabled before process death
        // or system kill. This ensures the foreground service survives reboots,
        // battery optimization kills, and Android's foreground service restrictions.
        lifecycleScope.launch {
            if (prefs.getMonitoringEnabled() && !ArrivalService.isRunning) {
                ArrivalService.start(this@MainActivity)
            }
        }

        answerPendingLocationRequests()

        val startDestination = if (userRepo.isLoggedIn()) Screen.Send.route else Screen.Login.route
        setContent {
            TextGateTheme {
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
