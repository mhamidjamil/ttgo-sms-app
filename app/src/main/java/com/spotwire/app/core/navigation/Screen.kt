package com.spotwire.app.core.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Signup : Screen("signup")
    data object PhoneVerify : Screen("phone_verify")
    data object EmailVerify : Screen("email_verify")
    data object Send : Screen("send")
    // Manual and automated messages share one History destination.
    data object History : Screen("history")
    // Arrival monitoring has its own tab instead of living under Profile.
    data object Arrival : Screen("arrival")
    // Where the day went: time per place, and the run of stops behind it.
    data object Timeline : Screen("timeline")
    data object Profile : Screen("profile")
    data object SettingsHistory : Screen("settings_history")
    data object MonitorLog : Screen("monitor_log")
    data object WhatsApp : Screen("whatsapp")
    data object AlertSources : Screen("alert_sources")
    data object LinkedAccounts : Screen("linked_accounts")

    // A linked person's timeline, opened from their card. The name is carried in
    // the route because the reader is only allowed the stays themselves, never
    // the account they came from.
    data object SharedTimeline : Screen("shared_timeline/{uid}/{name}/{placeId}") {
        fun route(uid: String, name: String, placeId: String?) =
            "shared_timeline/$uid/${name.ifBlank { "Their" }}/${placeId ?: ALL_PLACES}"

        const val ALL_PLACES = "all"
    }
}
