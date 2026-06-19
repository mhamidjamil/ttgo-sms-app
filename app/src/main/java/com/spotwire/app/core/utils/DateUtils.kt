package com.spotwire.app.core.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    fun todayString(): String = dateFmt.format(Date())

    fun dayString(date: Date?): String = date?.let { dateFmt.format(it) } ?: ""

    fun currentTimeHHmm(): String = timeFmt.format(Date())

    // The routine learner has to be taught the moment the person actually
    // arrived, which is minutes before the alert decision is taken.
    fun timeHHmm(atMillis: Long): String = timeFmt.format(Date(atMillis))

    fun minutesOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    // Human-facing 12-hour clock, e.g. "7:42 PM" — used in arrival messages.
    fun currentTime12h(): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date())

    // Same clock, for a moment that has already passed, so a message can name
    // the time the visit began instead of the time it was put together.
    fun time12h(atMillis: Long): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date(atMillis))

    fun formatTimestamp(date: Date?): String {
        if (date == null) return "—"
        return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(date)
    }
}
