package com.textgate.app.core.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    fun todayString(): String = dateFmt.format(Date())

    fun currentTimeHHmm(): String = timeFmt.format(Date())

    // Human-facing 12-hour clock, e.g. "7:42 PM" — used in arrival messages.
    fun currentTime12h(): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date())

    fun formatTimestamp(date: Date?): String {
        if (date == null) return "—"
        return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(date)
    }
}
