package com.textgate.app.core.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun todayString(): String = fmt.format(Date())

    fun formatTimestamp(date: Date?): String {
        if (date == null) return "—"
        return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(date)
    }
}
