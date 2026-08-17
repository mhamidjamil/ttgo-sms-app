package com.spotwire.app.presentation.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.data.local.VisitLogStore
import com.spotwire.app.domain.repository.LinkRepository
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
    private val linkRepo: LinkRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    // Set when the page is showing somebody else's timeline instead of this
    // phone's own. A place id narrows it to the one place that person is
    // trusted with, which is also the only query their grant can satisfy.
    private var otherUid: String? = null
    private var onlyPlaceId: String? = null

    init {
        load(TimelineWindow.TODAY)
    }

    fun showAccount(uid: String, placeId: String?) {
        otherUid = uid
        onlyPlaceId = placeId
        load(_uiState.value.window)
    }

    fun load(window: TimelineWindow) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, window = window, error = null)
            val from = startOf(window)
            val now = System.currentTimeMillis()
            val theirUid = otherUid
            if (theirUid != null) {
                showStretches(fromAccount(theirUid, from, now, onlyPlaceId), window)
                return@launch
            }
            // For my own timeline the phone's copy is the source: it is
            // complete, it needs no network, and it carries the stay that is
            // still going on. The account copy only matters on a phone that has
            // just been set up.
            val stays = withContext(Dispatchers.IO) { visitLog.stays() }
                .filter { it.to >= from }
                .sortedBy { it.from }
            val stretches = if (stays.isNotEmpty()) fill(stays, from, now)
                else fromAccount(userRepo.currentFirebaseUser()?.uid, from, now, null)
            showStretches(stretches, window)
        }
    }

    private fun showStretches(stretches: List<Stretch>, window: TimelineWindow) {
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

    /**
     * The month kept on the account. This is the whole story for somebody else's
     * timeline, and the fallback for my own on a phone that has just been set up
     * and has no stays of its own yet.
     */
    private suspend fun fromAccount(
        uid: String?,
        from: Long,
        now: Long,
        placeId: String?,
    ): List<Stretch> {
        if (uid == null) return emptyList()
        val visits = runCatching {
            if (otherUid == null) userRepo.getPlaceVisits(uid, from).first()
            else linkRepo.visitsOf(uid, from, placeId).first()
        }.onFailure {
            // A refusal here means the grant was taken back, which has to be
            // said. An empty page would read as "they went nowhere".
            _uiState.value = _uiState.value.copy(
                error = if (otherUid == null) "Could not read your timeline from the server"
                else "They are no longer sharing this with you",
            )
        }.getOrNull().orEmpty()
        if (visits.isEmpty()) return emptyList()
        val stays = visits.map { VisitLogStore.Stay(it.placeId, it.label, it.startedAt, it.endedAt) }
            .sortedBy { it.from }
        // Somebody trusted with one place is shown only the stays at it. Filling
        // the rest of the day in as untracked would be a lie: it was tracked,
        // they are simply not allowed to see it.
        if (placeId != null) return stays.map { Stretch(it.placeId, it.label, it.from, it.to) }
        return fill(stays, from, now)
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
