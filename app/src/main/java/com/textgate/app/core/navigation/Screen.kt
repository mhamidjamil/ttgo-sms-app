package com.textgate.app.core.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Signup : Screen("signup")
    data object PhoneVerify : Screen("phone_verify")
    data object Send : Screen("send")
    data object History : Screen("history")
    data object Auto : Screen("auto")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")
}
