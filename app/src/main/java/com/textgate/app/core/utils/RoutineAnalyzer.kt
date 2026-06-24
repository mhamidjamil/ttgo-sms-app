package com.textgate.app.core.utils

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class RoutineAnalyzer {

    /**
     * Returns effective stability wait in minutes.
     * If ≥5 past arrival times are available and the current time falls within μ±σ,
     * the wait is halved (down to MIN_STABILITY_MINUTES) — the device probably arrived
     * on its usual schedule, so we're more confident faster.
     */
    fun effectiveWait(arrivalTimes: List<String>, stabilityMinutes: Int): Int {
        if (arrivalTimes.size < 5) return stabilityMinutes
        val minutesList = arrivalTimes.takeLast(30).mapNotNull { parseToMinutes(it) }
        if (minutesList.size < 5) return stabilityMinutes

        val mean = minutesList.average()
        val variance = minutesList.sumOf { (it - mean).pow(2.0) } / minutesList.size
        val stdDev = sqrt(variance)

        return if (abs(currentMinutes() - mean) <= stdDev) {
            maxOf(WifiConfig.MIN_STABILITY_MINUTES, stabilityMinutes / 2)
        } else {
            stabilityMinutes
        }
    }

    private fun parseToMinutes(hhmm: String): Int? {
        val parts = hhmm.split(":").takeIf { it.size == 2 } ?: return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun currentMinutes(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }
}
