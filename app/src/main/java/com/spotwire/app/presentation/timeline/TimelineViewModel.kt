package com.spotwire.app.presentation.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.data.local.VisitLogStore
import com.spotwire.app.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * One stretch of the day as the page draws it. A gap in the record is a row of
 * its own rather than a silence, because "nothing was watching between two and
 * six" is an answer and a blank is not.
 */
data class Stretch(
    val placeId: String,
    val label: String,
    val from: Long,
    val to: Long,
    val tracked: Boolean = true,
) {
    val millis: Long get() = to - from
}

// How far back the page is showing.
enum class TimelineWindow(val label: String, val days: Int) {
    TODAY("Today", 1),
    WEEK("7 days", 7),
}

data class TimelineUiState(
    val isLoading: Boolean = true,
    val window: TimelineWindow = TimelineWindow.TODAY,
    val stretches: List<Stretch> = emptyList(),
    // Place label to time spent there, longest first. Untracked time is left
    // out: it is reported on its own line, because folding it into the chart
    // would make an evening with the WiFi off look like the biggest place of
    // the day.
    val totals: List<Pair<String, Long>> = emptyList(),
    val untrackedMillis: Long = 0L,
    val error: String? = null,
)

class TimelineViewModel(
    private val visitLog: VisitLogStore,
    private val userRepo: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        load(TimelineWindow.TODAY)
    }

    fun load(window: TimelineWindow) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, window = window, error = null)
            val from = startOf(window)
            val now = System.currentTimeMillis()
            // The phone's own copy is the source: it is complete, it needs no
            // network, and it carries the stay that is still going on. The
            // account copy only matters on a phone that has just been set up.
            val stays = withContext(Dispatchers.IO) { visitLog.stays() }
                .filter { it.to >= from }
                .sortedBy { it.from }
            val stretches = if (stays.isEmpty()) fromAccount(from, now) else fill(stays, from, now)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                window = window,
                stretches = stretches.reversed(),
                totals = stretches.filter { it.tracked }
                    .groupBy { it.label }
                    .map { (label, rows) -> label to rows.sumOf { it.millis } }
                    .sortedByDescending { it.second },
                untrackedMillis = stretches.filterNot { it.tracked }.sumOf { it.millis },
            )
        }
    }

    // A phone that has just been reinstalled has no stays of its own yet, so the
    // month kept on the account is all there is. It carries no in-progress stay,
    // which is why it is the fallback and not the source.
    private suspend fun fromAccount(from: Long, now: Long): List<Stretch> {
        val uid = userRepo.currentFirebaseUser()?.uid ?: return emptyList()
        val visits = runCatching { userRepo.getPlaceVisits(uid, from).first() }.onFailure {
            _uiState.value = _uiState.value.copy(error = "Could not read your timeline from the server")
        }.getOrNull().orEmpty()
        if (visits.isEmpty()) return emptyList()
        return fill(
            visits.map { VisitLogStore.Stay(it.placeId, it.label, it.startedAt, it.endedAt) }
                .sortedBy { it.from },
            from, now,
        )
    }

    /**
     * Turns the stays into an unbroken run of stretches, so every minute of the
     * window is accounted for either by a place or by an admission that nothing
     * was watching.
     */
    private fun fill(stays: List<VisitLogStore.Stay>, from: Long, now: Long): List<Stretch> = buildList {
        var cursor = from
        stays.forEach { stay ->
            val start = maxOf(stay.from, from)
            if (start - cursor >= GAP_WORTH_SHOWING_MILLIS) {
                add(Stretch(UNTRACKED_ID, "Not tracked", cursor, start, tracked = false))
            }
            add(Stretch(stay.placeId, stay.label, maxOf(cursor, start), maxOf(stay.to, start)))
            cursor = maxOf(cursor, stay.to)
        }
        if (now - cursor >= GAP_WORTH_SHOWING_MILLIS) {
            add(Stretch(UNTRACKED_ID, "Not tracked", cursor, now, tracked = false))
        }
    }

    private fun startOf(window: TimelineWindow): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -(window.days - 1))
    }.timeInMillis

    companion object {
        const val UNTRACKED_ID = "__untracked"
        // Checks are minutes apart at best, so a short hole is the cadence
        // rather than a gap in what is known, and drawing it would fill the page
        // with rows nobody needs.
        private const val GAP_WORTH_SHOWING_MILLIS = 20 * 60 * 1000L
    }
}
