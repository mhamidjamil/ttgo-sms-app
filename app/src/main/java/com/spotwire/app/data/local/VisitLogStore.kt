package com.spotwire.app.data.local

import org.json.JSONObject
import android.content.Context
import java.io.File

/**
 * A week of where this phone actually was, kept on the phone so the timeline
 * opens with no network and survives being offline all day.
 *
 * A stay is one continuous stretch at one place. It is written the moment
 * detection first sees the place and its end is pushed forward by every later
 * check that still sees it, so a stay in progress is already on file rather than
 * appearing only once it is over. That is what makes a process the system killed
 * cost nothing: whatever was written stands, and the hole after it reads as
 * untracked time instead of as hours credited to wherever the phone last was.
 *
 * Somewhere that was never saved is a stay too, under [UNKNOWN_ID]. Being
 * unable to look at all is NOT: nothing is written, and the gap it leaves is the
 * honest answer.
 */
class VisitLogStore(context: Context) {

    data class Stay(val placeId: String, val label: String, val from: Long, val to: Long) {
        val millis: Long get() = to - from
        val isUnknown: Boolean get() = placeId == UNKNOWN_ID
    }

    private val file = File(context.filesDir, "place_visits.jsonl")
    private val lock = Any()

    /**
     * Records that the phone was at [placeId] at [at]. Returns the stay this
     * ended, if any, so the caller can mirror a finished stay to the account;
     * null while the current stay is only being extended.
     */
    fun note(at: Long, placeId: String, label: String): Stay? = synchronized(lock) {
        val all = readAll()
        val last = all.lastOrNull()
        // Same place, and observation never lapsed: this is the same stay, so
        // push its end forward rather than starting another one.
        if (last != null && last.placeId == placeId && at - last.to <= GAP_THAT_ENDS_A_STAY_MILLIS) {
            writeAll(all.dropLast(1) + last.copy(to = at))
            return null
        }
        writeAll(all + Stay(placeId, label, at, at))
        // A stay seen exactly once is a single sample, not somewhere the phone
        // spent time, so it is not worth putting on the account.
        return last?.takeIf { it.millis >= WORTH_KEEPING_MILLIS }
    }

    /** Newest first, nothing older than the retention window. */
    fun stays(): List<Stay> = synchronized(lock) { readAll().sortedByDescending { it.from } }

    /**
     * The stays name where somebody has been, so signing out or deleting the
     * account has to take them with it. Emptied rather than removed, because
     * monitoring can be writing a line at the same moment.
     */
    fun clear() {
        synchronized(lock) { runCatching { if (file.exists()) file.writeText("") } }
    }

    // Oldest first, which is the order the file is in and the order note() needs.
    private fun readAll(): List<Stay> {
        if (!file.exists()) return emptyList()
        val floor = System.currentTimeMillis() - RETENTION_MILLIS
        return runCatching {
            file.readLines().mapNotNull { line ->
                runCatching {
                    val row = JSONObject(line)
                    Stay(row.getString("place"), row.getString("label"),
                        row.getLong("from"), row.getLong("to"))
                }.getOrNull()
            }.filter { it.to >= floor }
        }.getOrDefault(emptyList())
    }

    // A week of stays is dozens of lines, so rewriting the file on every check is
    // cheaper than appending and coalescing the duplicates back on every read.
    private fun writeAll(stays: List<Stay>) {
        runCatching {
            file.writeText(stays.joinToString("") { stay ->
                JSONObject()
                    .put("place", stay.placeId).put("label", stay.label)
                    .put("from", stay.from).put("to", stay.to)
                    .toString() + "\n"
            })
        }
    }

    companion object {
        // Anywhere that was never saved as a place.
        const val UNKNOWN_ID = "__unknown"
        const val UNKNOWN_LABEL = "Unknown place"

        private const val RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1000L
        // Checks run as rarely as every fifteen minutes, and further apart than
        // that means nobody was watching, so the stay ends where it was last
        // seen rather than swallowing the silence.
        private const val GAP_THAT_ENDS_A_STAY_MILLIS = 20 * 60 * 1000L
        // Under this a stay is a passing reading, not time spent somewhere.
        private const val WORTH_KEEPING_MILLIS = 5 * 60 * 1000L
    }
}
