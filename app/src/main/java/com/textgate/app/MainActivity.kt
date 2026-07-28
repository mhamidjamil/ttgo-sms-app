package com.textgate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.textgate.app.core.navigation.AppNavGraph
import com.textgate.app.core.navigation.Screen
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.services.ArrivalService
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val userRepo: UserRepository by inject()
    private val prefs: PreferencesDataSource by inject()

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

        val startDestination = if (userRepo.isLoggedIn()) Screen.Send.route else Screen.Login.route
        setContent {
            TextGateTheme {
                AppNavGraph(startDestination = startDestination)
            }
        }
    }
}
