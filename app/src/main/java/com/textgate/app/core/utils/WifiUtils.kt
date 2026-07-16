package com.textgate.app.core.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.textgate.app.domain.model.Place

private const val TAG = "TextGateWifi"

// Android hands back this placeholder instead of the real connected BSSID when
// the caller has no location permission, which is not a usable answer either.
private const val NO_BSSID = "02:00:00:00:00:00"

/**
 * Every access point the phone can HEAR right now, connected or not, lowercased.
 *
 * A saved place is "here" when its network is in range. The phone may sit on
 * mobile data all day and never join the home network, so the connected network
 * is only one more entry in this set, never the deciding one.
 */
fun visibleBssids(context: Context): Set<String> = visibleAccessPoints(context).keys

/**
 * Every access point the phone can hear right now, mapped to its signal strength
 * in dBm. Strength is what separates standing inside a building from walking
 * past it on the street, so the deciding read has to carry it.
 *
 * The connected network is folded in at a strength that always clears any
 * closeness floor: being joined to it is stronger evidence than any reading.
 */
@Suppress("DEPRECATION")
fun visibleAccessPoints(context: Context): Map<String, Int> {
    val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyMap()
    val scanned = try {
        wm.scanResults.mapNotNull { result ->
            result.BSSID?.lowercase()?.let { it to result.level }
        }
    } catch (e: Exception) {
        // Missing location permission or location services turned off.
        Log.w(TAG, "Scan results unavailable: ${e.message}")
        emptyList()
    }
    val connected = try {
        wm.connectionInfo?.bssid?.lowercase()?.takeIf { it != NO_BSSID }
    } catch (_: Exception) {
        null
    }
    return buildMap {
        scanned.forEach { (bssid, level) -> put(bssid, maxOf(level, get(bssid) ?: level)) }
        connected?.let { put(it, 0) }
    }
}

// Asks the framework for a fresh sweep. Android throttles this (four calls per
// two minutes) and a refused request simply leaves the cached results in place,
// so the return value is deliberately ignored.
@Suppress("DEPRECATION")
fun requestWifiScan(context: Context) {
    try {
        context.applicationContext.getSystemService(WifiManager::class.java)?.startScan()
    } catch (e: Exception) {
        Log.w(TAG, "startScan failed: ${e.message}")
    }
}

// The first saved place whose network is in range.
fun placeInRange(places: List<Place>, bssids: Set<String>): Place? =
    places.firstOrNull { place -> place.savedBssids.any { it in bssids } }

// Scanning still works with WiFi switched off when the system-wide "WiFi
// scanning always available" toggle is on, which is exactly the case for
// someone who lives on mobile data.
@Suppress("DEPRECATION")
fun canScanWifi(context: Context): Boolean {
    val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
    return wm.isWifiEnabled || wm.isScanAlwaysAvailable
}
