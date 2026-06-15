package com.textgate.app.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A day of monitoring activity, kept on the phone so "why did no alert come"
 * can be answered from the device instead of guessed at. One JSON object per
 * line; anything older than 24 hours is pruned as new rows land.
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

    data class Entry(val at: Long, val kind: String, val message: String)

    private val file = File(context.filesDir, "monitor_log.jsonl")
    private val lock = Any()
    private var appendsSincePrune = 0

    fun append(kind: String, message: String) {
        synchronized(lock) {
            runCatching {
                if (appendsSincePrune++ >= PRUNE_EVERY_APPENDS) {
                    appendsSincePrune = 0
                    writeAll(readAll())
                }
                file.appendText(encode(Entry(System.currentTimeMillis(), kind, message)) + "\n")
            }
        }
    }

    // Newest first, nothing older than a day.
    fun entries(): List<Entry> = synchronized(lock) { readAll().sortedByDescending { it.at } }

    private fun readAll(): List<Entry> {
        if (!file.exists()) return emptyList()
        val floor = System.currentTimeMillis() - RETENTION_MILLIS
        return runCatching {
            file.readLines().mapNotNull { line ->
                runCatching {
                    val row = JSONObject(line)
                    Entry(row.getLong("at"), row.getString("kind"), row.getString("message"))
                }.getOrNull()
            }.filter { it.at >= floor }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(entries: List<Entry>) {
        runCatching { file.writeText(entries.joinToString("") { encode(it) + "\n" }) }
    }

    private fun encode(entry: Entry): String = JSONObject()
        .put("at", entry.at).put("kind", entry.kind).put("message", entry.message).toString()

    companion object {
        private const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        // Pruning rereads the whole file, so it runs once in a while rather
        // than on every row.
        private const val PRUNE_EVERY_APPENDS = 200
    }
}
