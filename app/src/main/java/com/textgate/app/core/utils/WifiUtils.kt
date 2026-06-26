package com.textgate.app.core.utils

import android.content.Context
import android.net.wifi.WifiManager

// BSSID of the network we are currently on, or null when off WiFi. Android hands
// back the "02:00:00:00:00:00" placeholder instead of the real value when the
// caller has no location permission, which is not a usable answer either.
@Suppress("DEPRECATION")
fun currentBssid(context: Context): String? = try {
    context.applicationContext.getSystemService(WifiManager::class.java)
        ?.connectionInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }
} catch (_: Exception) {
    null
}
