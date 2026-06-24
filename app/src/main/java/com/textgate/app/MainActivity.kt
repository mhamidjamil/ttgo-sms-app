package com.textgate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.textgate.app.core.navigation.AppNavGraph
import com.textgate.app.core.navigation.Screen
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.domain.repository.UserRepository
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val userRepo: UserRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startDestination = if (userRepo.isLoggedIn()) Screen.Send.route else Screen.Login.route
        setContent {
            TextGateTheme {
                AppNavGraph(startDestination = startDestination)
            }
        }
    }
}
