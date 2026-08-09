package com.textgate.app.core.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Signup : Screen("signup")
    data object PhoneVerify : Screen("phone_verify")
    data object Send : Screen("send")
    // Manual and automated messages share one History destination.
    data object History : Screen("history")
    // Arrival monitoring has its own tab instead of living under Profile.
    data object Arrival : Screen("arrival")
    data object Profile : Screen("profile")
    data object SettingsHistory : Screen("settings_history")
    data object MonitorLog : Screen("monitor_log")
    data object WhatsApp : Screen("whatsapp")
    data object AlertSources : Screen("alert_sources")
    data object LinkedAccounts : Screen("linked_accounts")
}
