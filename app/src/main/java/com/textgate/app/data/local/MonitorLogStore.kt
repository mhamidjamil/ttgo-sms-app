package com.textgate.app.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Three days of monitoring activity, kept on the phone so "why did no alert
 * come" can be answered from the device instead of guessed at. One JSON object
 * per line; anything older than the window is pruned as new rows land. Three
 * days because a missed arrival is usually noticed a day or two later, and a
 * log that has already dropped it is no use.
 */
class MonitorLogStore(context: Context) {

    // What a row was, which is what the log page colors by: a routine check, a
    // state change worth reading, an alert that went out, or a problem that
    // stopped observation.
    object Kind {
        const val CHECK = "check"
        const val EVENT = "event"
        const val ALERT = "alert"
        const val PROBLEM = "problem"
    }

    // The detail is the working behind the row, such as what each place sounded
    // like on a check. Absent on rows that have nothing more to say.
    data class Entry(val at: Long, val kind: String, val message: String, val detail: String? = null)

    private val file = File(context.filesDir, "monitor_log.jsonl")
    private val lock = Any()
    // Starts due, so the first row written by each process prunes. The counter
    // only lives in memory, and this service is restarted often enough that a
    // count of 200 was rarely reached, which let the file grow forever.
    private var appendsSincePrune = PRUNE_EVERY_APPENDS

    fun append(kind: String, message: String, detail: String? = null) {
        synchronized(lock) {
            runCatching {
                if (appendsSincePrune++ >= PRUNE_EVERY_APPENDS) {
                    appendsSincePrune = 0
                    writeAll(readAll())
                }
                file.appendText(encode(Entry(System.currentTimeMillis(), kind, message, detail)) + "\n")
            }
        }
    }

    // Newest first, nothing older than the retention window.
    fun entries(): List<Entry> = synchronized(lock) { readAll().sortedByDescending { it.at } }

    /**
     * The retained log as plain text for sending off the phone. Oldest first,
     * because a diagnosis is read forwards even though the page shows the newest
     * row on top.
     */
    fun exportText(): String = synchronized(lock) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        readAll().sortedBy { it.at }.joinToString("\n") { entry ->
            val line = "${stamp.format(Date(entry.at))}  ${entry.kind.uppercase().padEnd(9)}${entry.message}"
            if (entry.detail.isNullOrBlank()) line
            else line + entry.detail.lines().joinToString("") { "\n    $it" }
        }
    }

    // A copy to hand to the share sheet, in the cache directory because nothing
    // reads it back: it is written fresh every time the button is pressed.
    fun exportFile(context: Context): File {
        val export = File(File(context.cacheDir, "logs").apply { mkdirs() }, "textgate-monitor-log.txt")
        export.writeText(exportText())
        return export
    }

    private fun readAll(): List<Entry> {
        if (!file.exists()) return emptyList()
        val floor = System.currentTimeMillis() - RETENTION_MILLIS
        return runCatching {
            file.readLines().mapNotNull { line ->
                runCatching {
                    val row = JSONObject(line)
                    Entry(row.getLong("at"), row.getString("kind"), row.getString("message"),
                        row.optString("detail").ifBlank { null })
                }.getOrNull()
            }.filter { it.at >= floor }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(entries: List<Entry>) {
        runCatching { file.writeText(entries.joinToString("") { encode(it) + "\n" }) }
    }

    private fun encode(entry: Entry): String = JSONObject()
        .put("at", entry.at).put("kind", entry.kind).put("message", entry.message)
        .apply { entry.detail?.let { put("detail", it) } }.toString()

    companion object {
        private const val RETENTION_MILLIS = 72 * 60 * 60 * 1000L
        // Pruning rereads the whole file, so it runs once in a while rather
        // than on every row.
        private const val PRUNE_EVERY_APPENDS = 200
    }
}
