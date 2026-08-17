package com.spotwire.app.core.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.spotwire.app.domain.model.Place
import kotlinx.coroutines.delay

private const val TAG = "SpotwireWifi"

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

/**
 * What the phone last heard, read straight from the scan cache without asking
 * for a sweep of its own. A cached read costs nothing and needs no scan budget,
 * which is what makes it safe to run from a fifteen minute alarm.
 */
fun cachedVisibleBssids(context: Context): Set<String> = cachedVisibleAccessPoints(context).keys

/**
 * The same cached read, with the signal strengths, so an alarm can ask the same
 * "where am I" question the sweep asks instead of settling for "is any of this
 * place's network in the list".
 */
@Suppress("DEPRECATION")
fun cachedVisibleAccessPoints(context: Context): Map<String, Int> {
    if (!canScanWifi(context)) return emptyMap()
    val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyMap()
    return try {
        wm.scanResults.mapNotNull { result ->
            result.BSSID?.lowercase()?.let { it to result.level }
        }.toMap()
    } catch (e: Exception) {
        Log.w(TAG, "Cached scan results unavailable: ${e.message}")
        emptyMap()
    }
}

// One access point as it is reported to somebody asking where this phone is.
// The NAME is the part that answers the question: "Al-Madina Store" says where
// a child stopped, a pair of coordinates and a hardware id do not.
data class VisibleNetwork(val ssid: String, val bssid: String, val level: Int)

/**
 * The networks around the phone, loudest first, read straight from the scan
 * cache.
 *
 * Deliberately no [requestWifiScan]: Android allows four sweeps per two minutes
 * and the arrival engine's whole cadence is built around that budget, so a
 * location answer asking for its own sweep would starve detection. The sweeps
 * already running keep this cache warm.
 */
@Suppress("DEPRECATION")
fun visibleNetworks(context: Context, limit: Int = 20): List<VisibleNetwork> {
    if (!canScanWifi(context)) return emptyList()
    val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
    return try {
        wm.scanResults.mapNotNull { result ->
            val bssid = result.BSSID?.lowercase() ?: return@mapNotNull null
            VisibleNetwork(result.SSID.orEmpty(), bssid, result.level)
        }.sortedByDescending { it.level }.take(limit)
    } catch (e: Exception) {
        Log.w(TAG, "Scan results unavailable: ${e.message}")
        emptyList()
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

// The first saved place whose network is in range. Deliberately loose, and only
// good enough for answering a linked account's "where are you?" from a single
// read; anything that decides an arrival goes through resolvePresence.
fun placeInRange(places: List<Place>, bssids: Set<String>): Place? =
    places.firstOrNull { place -> place.savedBssids.any { it in bssids } }

// How much louder the winning place has to be than the runner-up. Signal
// strength wanders by a few dB while standing still, so anything closer than
// this is not a real difference.
const val CONTEST_MARGIN_DBM = 8

// How long a freshly requested scan gets to deliver results before an empty
// cache is taken at its word.
private const val SCAN_RESULTS_WAIT_MILLIS = 6_000L

// What one saved place sounds like on a single read.
data class PlaceReading(
    val place: Place,
    val heardCount: Int,
    // The loudest of this place's networks that was actually heard, in dBm.
    // Int.MIN_VALUE when none of them was, so a place nobody can hear never
    // wins a comparison.
    val strongest: Int,
    // Enough of its networks heard, loud enough for its own closeness setting.
    val present: Boolean,
)

// One answer to "where am I", plus the working that produced it.
data class PresenceReading(
    val readings: List<PlaceReading>,
    val winner: Place?,
    // Two or more places are in range and none of them is clearly the nearest.
    val contested: Boolean,
) {
    // The two loudest places in range, which is what a tie has to name.
    val closest: List<PlaceReading>
        get() = readings.filter { it.present }.sortedByDescending { it.strongest }.take(2)
}

/**
 * Where the phone is, by the one rule the whole app uses: the sweep, "Where am
 * I?" and "Test detection here" all ask this and therefore agree.
 *
 * "Where am I" is one answer, not one per place. A home above a shop or an
 * office across the road can both be audible from the same spot, and alerting
 * for both means one of the two is always wrong. The loudest wins, and only by
 * a clear margin: a tie is an honest "cannot tell", which sends nothing.
 */
fun resolvePresence(places: List<Place>, visible: Map<String, Int>): PresenceReading {
    val readings = places.map { place ->
        val heard = place.savedBssids.mapNotNull { visible[it] }
        PlaceReading(
            place = place,
            heardCount = heard.size,
            strongest = heard.maxOrNull() ?: Int.MIN_VALUE,
            present = place.isPresentIn(visible),
        )
    }
    val ranked = readings.filter { it.present }.sortedByDescending { it.strongest }
    val winner = when {
        ranked.isEmpty() -> null
        ranked.size == 1 -> ranked.first().place
        ranked[0].strongest - ranked[1].strongest >= CONTEST_MARGIN_DBM -> ranked.first().place
        else -> null
    }
    return PresenceReading(readings, winner, winner == null && ranked.size > 1)
}

/**
 * A read a button can trust. Asking for a scan and reading the cache in the
 * same breath answers from wherever the phone was the last time anything
 * scanned, which is how a screen claimed a place the engine had already
 * rejected. Waiting once is what the sweep does, so the screens match it.
 */
suspend fun freshScan(context: Context): Map<String, Int> {
    requestWifiScan(context)
    val heard = visibleAccessPoints(context)
    if (heard.isNotEmpty()) return heard
    delay(SCAN_RESULTS_WAIT_MILLIS)
    return visibleAccessPoints(context)
}

/**
 * Why a sweep cannot observe anything right now, as a sentence the user can act
 * on, or null when scanning should work. Every one of these causes looks
 * identical from the outside (an empty scan list), which is how "scanning or
 * location is off" ended up on screen for someone whose settings were fine.
 *
 * Scanning still works with WiFi switched off when the system-wide "WiFi
 * scanning always available" toggle is on, which is exactly the case for
 * someone who lives on mobile data. Device location being switched off blocks
 * scan results outright, so it belongs in the same answer: without it the app
 * reports itself healthy while receiving nothing.
 */
@Suppress("DEPRECATION")
fun scanBlocker(context: Context): String? {
    val app = context.applicationContext
    val wm = app.getSystemService(WifiManager::class.java)
        ?: return "This phone has no usable WiFi"
    // Approximate location is not enough: Android hands coarse-only callers an
    // empty scan list without any error, so it has to be named here or the
    // failure is invisible.
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) return "Precise location permission is needed, allow it in the app's settings"
    if (app.getSystemService(LocationManager::class.java)?.isLocationEnabled == false)
        return "Device location is switched off"
    if (!wm.isWifiEnabled && !wm.isScanAlwaysAvailable)
        return "WiFi and WiFi scanning are both switched off"
    return null
}

fun canScanWifi(context: Context): Boolean = scanBlocker(context) == null
